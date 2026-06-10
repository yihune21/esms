package et.com.cog.esms.core.workspace;

import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Workspace CRUD + member management.
 * Reference: LLD §6.2
 */
@Slf4j
@RestController
@RequestMapping("/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceRepository workspaceRepo;
    private final RoleRepository roleRepo;
    private final et.com.cog.esms.core.identity.UserRepository userRepo;
    private final et.com.cog.esms.core.identity.WorkspaceMemberRepository memberRepo;
    private final WorkspacePermissionRepository permissionRepo;

    // ── GET /workspaces ──────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<WorkspaceDto>> list() {
        String roleCode = WorkspaceContext.current() != null
                ? WorkspaceContext.current().getRoleCode() : null;

        List<Workspace> workspaces;
        if ("SUPER_ADMIN".equals(roleCode)) {
            workspaces = workspaceRepo.findAll();
        } else {
            UUID userId = WorkspaceContext.currentUserId();
            workspaces = memberRepo.findByUserId(userId).stream()
                    .map(m -> m.getWorkspace())
                    .collect(Collectors.toList());
        }

        List<WorkspaceDto> dtos = workspaces.stream()
                .map(ws -> {
                    List<String> perms = permissionRepo.findByWorkspaceId(ws.getId())
                            .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
                    return toDto(ws, perms);
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    // ── POST /workspaces (Super Admin only) ──────────────────────
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateWorkspaceRequest req) {
        if (workspaceRepo.existsByCode(req.getCode())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "Workspace code already exists"));
        }

        Workspace ws = Workspace.builder()
                .code(req.getCode())
                .name(req.getName())
                .kind(req.getKind() != null ? req.getKind() : "GENERIC")
                .division(req.getDivision())
                .status("ACTIVE")
                .build();

        Workspace savedWs = workspaceRepo.save(ws);

        // Add permissions
        if (req.getPermissions() != null) {
            for (String perm : req.getPermissions()) {
                permissionRepo.save(new WorkspacePermission(savedWs.getId(), perm));
            }
        }

        // Assign Admin if provided
        if (req.getAdminUserId() != null) {
            roleRepo.findByCode("DEPT_HEAD").ifPresent(role -> {
                userRepo.findById(req.getAdminUserId()).ifPresent(user -> {
                    memberRepo.save(et.com.cog.esms.core.identity.WorkspaceMember.builder()
                            .workspace(savedWs)
                            .user(user)
                            .role(role)
                            .assignedAt(java.time.Instant.now())
                            .assignedBy(WorkspaceContext.currentUserId())
                            .build());
                });
            });
        }

        List<String> savedPerms = req.getPermissions() != null ? req.getPermissions() : List.of();
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(savedWs, savedPerms));
    }

    // ── GET /workspaces/{id} ─────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<WorkspaceDto> get(@PathVariable UUID id) {
        return workspaceRepo.findById(id)
                .map(ws -> {
                    List<String> perms = permissionRepo.findByWorkspaceId(id)
                            .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
                    return ResponseEntity.ok(toDto(ws, perms));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH /workspaces/{id} ───────────────────────────────────
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> updates) {
        return workspaceRepo.findById(id)
                .map(ws -> {
                    if (updates.containsKey("name")) ws.setName((String) updates.get("name"));
                    if (updates.containsKey("division")) ws.setDivision((String) updates.get("division"));
                    if (updates.containsKey("senderMask")) ws.setSenderMask((String) updates.get("senderMask"));
                    if (updates.containsKey("dailySmsLimit")) ws.setDailySmsLimit((Integer) updates.get("dailySmsLimit"));
                    workspaceRepo.save(ws);
                    
                    if (updates.containsKey("permissions")) {
                        @SuppressWarnings("unchecked")
                        List<String> perms = (List<String>) updates.get("permissions");
                        permissionRepo.deleteByWorkspaceId(id);
                        for (String perm : perms) {
                            permissionRepo.save(new WorkspacePermission(id, perm));
                        }
                    }

                    List<String> currentPerms = permissionRepo.findByWorkspaceId(id)
                            .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
                    return ResponseEntity.ok(toDto(ws, currentPerms));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── POST /workspaces/{id}/deactivate ─────────────────────────
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> deactivate(@PathVariable UUID id) {
        return workspaceRepo.findById(id).map(ws -> {
            ws.setStatus("SUSPENDED");
            workspaceRepo.save(ws);
            List<String> perms = permissionRepo.findByWorkspaceId(id)
                    .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
            return ResponseEntity.ok(toDto(ws, perms));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── POST /workspaces/{id}/activate ───────────────────────────
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> activate(@PathVariable UUID id) {
        return workspaceRepo.findById(id).map(ws -> {
            ws.setStatus("ACTIVE");
            workspaceRepo.save(ws);
            List<String> perms = permissionRepo.findByWorkspaceId(id)
                    .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
            return ResponseEntity.ok(toDto(ws, perms));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── GET /workspaces/{id}/members ─────────────────────────────
    @GetMapping("/{id}/members")
    public ResponseEntity<List<Map<String, Object>>> members(@PathVariable UUID id) {
        var members = memberRepo.findByWorkspaceId(id);
        var result = members.stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", m.getUser().getId());
            map.put("username", m.getUser().getUsername());
            map.put("displayName", m.getUser().getDisplayName());
            map.put("role", m.getRole().getCode());
            map.put("assignedAt", m.getAssignedAt());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ── POST /workspaces/{id}/members ────────────────────────────
    @PostMapping("/{id}/members")
    @PreAuthorize("hasAuthority('WORKSPACE_MEMBER_ADD')")
    public ResponseEntity<?> addMember(@PathVariable UUID id,
                                       @Valid @RequestBody AddMemberRequest req) {
        var wsOpt = workspaceRepo.findById(id);
        var userOpt = userRepo.findById(req.getUserId());
        var roleOpt = roleRepo.findById(req.getRoleId());

        if (wsOpt.isEmpty() || userOpt.isEmpty() || roleOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("title", "Invalid workspace, user, or role"));
        }

        if (memberRepo.existsByWorkspaceIdAndUserId(id, req.getUserId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "User is already a member"));
        }

        var member = et.com.cog.esms.core.identity.WorkspaceMember.builder()
                .workspace(wsOpt.get())
                .user(userOpt.get())
                .role(roleOpt.get())
                .assignedAt(java.time.Instant.now())
                .assignedBy(WorkspaceContext.currentUserId())
                .build();

        memberRepo.save(member);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "Member added"));
    }

    // ── Helpers ──────────────────────────────────────────────────

    private WorkspaceDto toDto(Workspace ws, List<String> permissions) {
        return new WorkspaceDto(ws.getId(), ws.getCode(), ws.getName(),
                ws.getKind(), ws.getDivision(), ws.getStatus(), ws.getSenderMask(),
                ws.getDailySmsLimit(), permissions);
    }

    // ── DTOs ─────────────────────────────────────────────────────

    @Data @AllArgsConstructor
    public static class WorkspaceDto {
        private UUID id;
        private String code;
        private String name;
        private String kind;
        private String division;
        private String status;
        private String senderMask;
        private Integer dailySmsLimit;
        private List<String> permissions;
    }

    @Data
    public static class CreateWorkspaceRequest {
        @NotBlank private String code;
        @NotBlank private String name;
        private String kind;
        private String division;
        private UUID adminUserId;
        private List<String> permissions;
    }

    @Data
    public static class AddMemberRequest {
        private UUID userId;
        private UUID roleId;
    }
}
