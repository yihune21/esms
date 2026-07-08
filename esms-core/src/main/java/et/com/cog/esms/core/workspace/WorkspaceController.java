package et.com.cog.esms.core.workspace;

import et.com.cog.esms.core.identity.WorkspaceMember;
import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;


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

        if (req.getPermissions() != null) {
            for (String perm : req.getPermissions()) {
                permissionRepo.save(new WorkspacePermission(savedWs.getId(), perm));
            }
        }

        if (req.getAdminUserId() != null) {
            roleRepo.findByCode("DEPT_HEAD").ifPresent(role -> {
                userRepo.findById(req.getAdminUserId()).ifPresent(user -> {
                    memberRepo.save(WorkspaceMember.builder()
                            .workspace(savedWs)
                            .user(user)
                            .role(role)
                            .assignedAt(java.time.Instant.now())
                            .assignedBy(WorkspaceContext.currentUserId())
                            .build());
                });
            });
        }

        boolean hasDelegation = req.getPermissions() != null && req.getPermissions().contains("DELEGATION");
        if (hasDelegation && req.getDelegateUserId() != null) {
            roleRepo.findByCode("CEO").ifPresentOrElse(
                ceoRole -> userRepo.findById(req.getDelegateUserId()).ifPresent(user ->
                    memberRepo.save(WorkspaceMember.builder()
                            .workspace(savedWs)
                            .user(user)
                            .role(ceoRole)
                            .assignedAt(java.time.Instant.now())
                            .assignedBy(WorkspaceContext.currentUserId())
                            .build())),
                () -> log.warn("CEO role not found — delegate {} not assigned for workspace {}",
                        req.getDelegateUserId(), savedWs.getId())
            );
        }

        List<String> savedPerms = req.getPermissions() != null ? req.getPermissions() : List.of();
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(savedWs, savedPerms));
    }

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

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> updates) {
        return workspaceRepo.findById(id)
                .map(ws -> {
                    if (updates.containsKey("name")) ws.setName((String) updates.get("name"));
                    if (updates.containsKey("division")) ws.setDivision((String) updates.get("division"));
                    if (updates.containsKey("status")) ws.setStatus((String) updates.get("status"));
                    if (updates.containsKey("senderMask")) ws.setSenderMask((String) updates.get("senderMask"));
                    if (updates.containsKey("dailySmsLimit")) {
                        Object raw = updates.get("dailySmsLimit");
                        if (raw instanceof Number) ws.setDailySmsLimit(((Number) raw).intValue());
                    }
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
                            roleRepo.findByCode("DEPT_HEAD").ifPresent(deptHeadRole -> {
                                memberRepo.findByWorkspaceId(id).stream()
                                        .filter(m -> "DEPT_HEAD".equals(m.getRole().getCode())
                                                && !m.getUser().getId().equals(adminId))
                                        .forEach(m -> {
                                            roleRepo.findByCode("OPERATOR").ifPresent(opRole -> {
                                                m.setRole(opRole);
                                                memberRepo.save(m);
                                            });
                                        });
                                userRepo.findById(adminId).ifPresent(user -> {
                                    memberRepo.findByWorkspaceIdAndUserId(id, adminId)
                                            .ifPresentOrElse(
                                                    member -> {
                                                        member.setRole(deptHeadRole);
                                                        memberRepo.save(member);
                                                    },
                                                    () -> memberRepo.save(WorkspaceMember.builder()
                                                            .workspace(ws)
                                                            .user(user)
                                                            .role(deptHeadRole)
                                                            .assignedAt(java.time.Instant.now())
                                                            .assignedBy(WorkspaceContext.currentUserId())
                                                            .build())
                                            );
                                });
                            });
                        }
                    }

               
                    boolean delegationKeyPresent = updates.containsKey("delegateUserId");
                    List<String> currentPermsForCheck = permissionRepo.findByWorkspaceId(id)
                            .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
                    boolean delegationFeatureActive = currentPermsForCheck.contains("DELEGATION")
                            || (updates.containsKey("permissions")
                                && ((List<?>) updates.get("permissions")).contains("DELEGATION"));

                    if (delegationKeyPresent) {
                        String delegateIdStr = (String) updates.get("delegateUserId");

                        if (delegateIdStr == null || delegateIdStr.isBlank() || !delegationFeatureActive) {
                            memberRepo.findByWorkspaceId(id).stream()
                                    .filter(m -> "CEO".equals(m.getRole().getCode()))
                                    .forEach(memberRepo::delete);
                        } else {
                            UUID delegateId = UUID.fromString(delegateIdStr);
                            roleRepo.findByCode("CEO").ifPresent(ceoRole -> {
                                memberRepo.findByWorkspaceId(id).stream()
                                        .filter(m -> "CEO".equals(m.getRole().getCode())
                                                && !m.getUser().getId().equals(delegateId))
                                        .forEach(memberRepo::delete);

                                userRepo.findById(delegateId).ifPresent(user ->
                                    memberRepo.findByWorkspaceIdAndUserId(id, delegateId)
                                            .ifPresentOrElse(
                                                    member -> { member.setRole(ceoRole); memberRepo.save(member); },
                                                    () -> memberRepo.save(
                                                            WorkspaceMember.builder()
                                                                    .workspace(ws)
                                                                    .user(user)
                                                                    .role(ceoRole)
                                                                    .assignedAt(java.time.Instant.now())
                                                                    .assignedBy(WorkspaceContext.currentUserId())
                                                                    .build()))
                                );
                            });
                        }
                    } else if (!delegationFeatureActive) {
                        memberRepo.findByWorkspaceId(id).stream()
                                .filter(m -> "CEO".equals(m.getRole().getCode()))
                                .forEach(memberRepo::delete);
                    }

                    List<String> currentPerms = permissionRepo.findByWorkspaceId(id)
                            .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
                    return ResponseEntity.ok(toDto(ws, currentPerms));
                })
                .orElse(ResponseEntity.notFound().build());
    }

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

        var member = WorkspaceMember.builder()
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

    
    @PatchMapping("/{id}/members/{userId}")
    @PreAuthorize("hasAuthority('WORKSPACE_MEMBER_ADD')")
    @Transactional
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
        String currentRole = member.getRole().getCode();
        String newRole = roleOpt.get().getCode();

        boolean isDemotingLastDeptHead = "DEPT_HEAD".equals(currentRole)
                && !"DEPT_HEAD".equals(newRole)
                && memberRepo.findByWorkspaceId(id).stream()
                       .filter(m -> "DEPT_HEAD".equals(m.getRole().getCode()))
                       .count() == 1;
        if (isDemotingLastDeptHead) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title",
                            "Cannot demote the last department head. Assign a new head first."));
        }

        member.setRole(roleOpt.get());
        memberRepo.save(member);

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "workspaceId", id,
                "role", roleOpt.get().getCode()
        ));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasAuthority('WORKSPACE_MEMBER_REMOVE')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        if (!memberRepo.existsByWorkspaceIdAndUserId(id, userId)) {
            return ResponseEntity.notFound().build();
        }
        boolean isLastDeptHead = memberRepo.findByWorkspaceId(id).stream()
                .filter(m -> "DEPT_HEAD".equals(m.getRole().getCode()))
                .count() == 1
                && memberRepo.findByWorkspaceIdAndUserId(id, userId)
                       .map(m -> "DEPT_HEAD".equals(m.getRole().getCode()))
                       .orElse(false);
        if (isLastDeptHead) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title",
                            "Cannot remove the last department head. Assign a new head first."));
        }
        memberRepo.deleteByWorkspaceIdAndUserId(id, userId);
        return ResponseEntity.noContent().build();
    }


    private WorkspaceDto toDto(Workspace ws, List<String> permissions) {
        return toDtoEnriched(ws, permissions);
    }

    private WorkspaceDto toDtoEnriched(Workspace ws, List<String> permissions) {
        long memberCount = memberRepo.findByWorkspaceId(ws.getId()).size();

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
        private UUID adminUserId;
        
        private UUID delegateUserId;
        private List<String> permissions;
    }

    @Data
    public static class AddMemberRequest {
        private UUID userId;
        private UUID roleId;
    }
}
