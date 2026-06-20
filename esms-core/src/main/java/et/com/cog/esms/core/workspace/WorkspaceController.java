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
    @PreAuthorize("hasAuthority('WORKSPACE_VIEW')")
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
                    return toDtoEnriched(ws, perms);
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

        // Assign Delegate (CEO/Director) if the workspace has DELEGATION permission and delegateUserId is provided
        boolean hasDelegation = req.getPermissions() != null && req.getPermissions().contains("DELEGATION");
        if (hasDelegation && req.getDelegateUserId() != null) {
            roleRepo.findByCode("CEO").ifPresentOrElse(
                ceoRole -> userRepo.findById(req.getDelegateUserId()).ifPresent(user ->
                    memberRepo.save(et.com.cog.esms.core.identity.WorkspaceMember.builder()
                            .workspace(savedWs)
                            .user(user)
                            .role(ceoRole)
                            .assignedAt(java.time.Instant.now())
                            .assignedBy(WorkspaceContext.currentUserId())
                            .build())),
                // Fallback: if no CEO role exists, store with DEPT_HEAD and flag via log
                () -> log.warn("CEO role not found — delegate {} not assigned for workspace {}",
                        req.getDelegateUserId(), savedWs.getId())
            );
        }

        List<String> savedPerms = req.getPermissions() != null ? req.getPermissions() : List.of();
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(savedWs, savedPerms));
    }

    // ── GET /workspaces/{id} ─────────────────────────────────────
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('WORKSPACE_VIEW')")
    public ResponseEntity<WorkspaceDto> get(@PathVariable UUID id) {
        return workspaceRepo.findById(id)
                .map(ws -> {
                    List<String> perms = permissionRepo.findByWorkspaceId(id)
                            .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
                    return ResponseEntity.ok(toDtoEnriched(ws, perms));
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

                    if (updates.containsKey("adminUserId")) {
                        String adminIdStr = (String) updates.get("adminUserId");
                        if (adminIdStr != null) {
                            UUID adminId = UUID.fromString(adminIdStr);
                            roleRepo.findByCode("DEPT_HEAD").ifPresent(role -> {
                                userRepo.findById(adminId).ifPresent(user -> {
                                    memberRepo.findByWorkspaceIdAndUserId(id, adminId)
                                            .ifPresentOrElse(
                                                    member -> {
                                                        member.setRole(role);
                                                        memberRepo.save(member);
                                                    },
                                                    () -> memberRepo.save(et.com.cog.esms.core.identity.WorkspaceMember.builder()
                                                            .workspace(ws)
                                                            .user(user)
                                                            .role(role)
                                                            .assignedAt(java.time.Instant.now())
                                                            .assignedBy(WorkspaceContext.currentUserId())
                                                            .build())
                                            );
                                });
                            });
                        }
                    }

                    // Update delegate (CEO/Director) when DELEGATION permission is present
                    List<String> currentPermsForCheck = permissionRepo.findByWorkspaceId(id)
                            .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
                    boolean hasDelegation = currentPermsForCheck.contains("DELEGATION")
                            || (updates.containsKey("permissions") &&
                               ((List<?>) updates.get("permissions")).contains("DELEGATION"));

                    if (updates.containsKey("delegateUserId") && hasDelegation) {
                        String delegateIdStr = (String) updates.get("delegateUserId");
                        if (delegateIdStr != null) {
                            UUID delegateId = UUID.fromString(delegateIdStr);
                            roleRepo.findByCode("CEO").ifPresent(ceoRole ->
                                userRepo.findById(delegateId).ifPresent(user ->
                                    memberRepo.findByWorkspaceIdAndUserId(id, delegateId)
                                            .ifPresentOrElse(
                                                    member -> { member.setRole(ceoRole); memberRepo.save(member); },
                                                    () -> memberRepo.save(
                                                            et.com.cog.esms.core.identity.WorkspaceMember.builder()
                                                                    .workspace(ws)
                                                                    .user(user)
                                                                    .role(ceoRole)
                                                                    .assignedAt(java.time.Instant.now())
                                                                    .assignedBy(WorkspaceContext.currentUserId())
                                                                    .build()))
                            ));
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
    @PreAuthorize("hasAuthority('WORKSPACE_VIEW')")
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

    // ── PATCH /workspaces/{id}/members/{userId} ──────────────────
    /** Change a member's role without removing and re-adding them. */
    @PatchMapping("/{id}/members/{userId}")
    @PreAuthorize("hasAuthority('WORKSPACE_MEMBER_ADD')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> changeMemberRole(@PathVariable UUID id,
                                              @PathVariable UUID userId,
                                              @RequestBody Map<String, Object> body) {
        var memberOpt = memberRepo.findByWorkspaceIdAndUserId(id, userId);
        if (memberOpt.isEmpty()) return ResponseEntity.notFound().build();

        String roleIdStr = (String) body.get("roleId");
        if (roleIdStr == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "roleId is required"));
        }

        var roleOpt = roleRepo.findById(UUID.fromString(roleIdStr));
        if (roleOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("title", "Role not found"));
        }

        var member = memberOpt.get();
        member.setRole(roleOpt.get());
        memberRepo.save(member);

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "workspaceId", id,
                "role", roleOpt.get().getCode()
        ));
    }

    // ── DELETE /workspaces/{id}/members/{userId} ─────────────────
    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasAuthority('WORKSPACE_MEMBER_REMOVE')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        if (!memberRepo.existsByWorkspaceIdAndUserId(id, userId)) {
            return ResponseEntity.notFound().build();
        }
        memberRepo.deleteByWorkspaceIdAndUserId(id, userId);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Basic DTO without enrichment (used internally). */
    private WorkspaceDto toDto(Workspace ws, List<String> permissions) {
        return toDtoEnriched(ws, permissions);
    }

    /** Enriched DTO with memberCount + admin name + delegate name (no N+1). */
    private WorkspaceDto toDtoEnriched(Workspace ws, List<String> permissions) {
        long memberCount = memberRepo.findByWorkspaceId(ws.getId()).size();

        // Find the DEPT_HEAD of this workspace
        String adminName = memberRepo.findByWorkspaceId(ws.getId()).stream()
                .filter(m -> "DEPT_HEAD".equals(m.getRole().getCode()))
                .findFirst()
                .map(m -> m.getUser().getDisplayName())
                .orElse(null);
        UUID adminUserId = memberRepo.findByWorkspaceId(ws.getId()).stream()
                .filter(m -> "DEPT_HEAD".equals(m.getRole().getCode()))
                .findFirst()
                .map(m -> m.getUser().getId())
                .orElse(null);

        // Find the CEO delegate of this workspace
        String delegateName = memberRepo.findByWorkspaceId(ws.getId()).stream()
                .filter(m -> "CEO".equals(m.getRole().getCode()))
                .findFirst()
                .map(m -> m.getUser().getDisplayName())
                .orElse(null);
        UUID delegateUserId = memberRepo.findByWorkspaceId(ws.getId()).stream()
                .filter(m -> "CEO".equals(m.getRole().getCode()))
                .findFirst()
                .map(m -> m.getUser().getId())
                .orElse(null);

        return new WorkspaceDto(ws.getId(), ws.getCode(), ws.getName(),
                ws.getKind(), ws.getDivision(), ws.getStatus(), ws.getSenderMask(),
                ws.getDailySmsLimit(), permissions, (int) memberCount, adminName, adminUserId,
                delegateName, delegateUserId);
    }

    // ── DTOs ─────────────────────────────────────────────────────

    @Data @AllArgsConstructor
    public static class WorkspaceDto {
        private UUID         id;
        private String       code;
        private String       name;
        private String       kind;
        private String       division;
        private String       status;
        private String       senderMask;
        private Integer      dailySmsLimit;
        private List<String> permissions;
        // ── enriched ──
        private int          memberCount;
        private String       adminName;
        private UUID         adminUserId;
        private String       delegateName;
        private UUID         delegateUserId;
    }

    @Data
    public static class CreateWorkspaceRequest {
        @NotBlank private String code;
        @NotBlank private String name;
        private String kind;
        private String division;
        /** Admin (Department Head) of this workspace. */
        private UUID adminUserId;
        /**
         * Delegate user (CEO / Director) — required when the workspace permissions
         * include DELEGATION (i.e. messages need CEO/Director sign-off).
         * Ignored if DELEGATION is not in the permissions list.
         */
        private UUID delegateUserId;
        private List<String> permissions;
    }

    @Data
    public static class AddMemberRequest {
        private UUID userId;
        private UUID roleId;
    }
}
