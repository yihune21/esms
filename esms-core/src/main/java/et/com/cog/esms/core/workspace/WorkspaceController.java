package et.com.cog.esms.core.workspace;

import et.com.cog.esms.core.identity.UserRepository;
import et.com.cog.esms.core.identity.WorkspaceMember;
import et.com.cog.esms.core.identity.WorkspaceMemberRepository;
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

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@RestController
@RequestMapping("/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceRepository workspaceRepo;
    private final RoleRepository roleRepo;
    private final UserRepository userRepo;
    private final WorkspaceMemberRepository memberRepo;
    private final WorkspacePermissionRepository permissionRepo;
    private final et.com.cog.esms.core.campaign.CampaignService campaignService;

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
    @Transactional
    public ResponseEntity<?> create(@Valid @RequestBody CreateWorkspaceRequest req) {
        if (workspaceRepo.existsByCode(req.getCode())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "Workspace code already exists"));
        }

        // ── Case 1: a branch under an existing umbrella ───────────────────────
        // A branch behaves like a standalone workspace (own admin, members,
        // data) but inherits the umbrella's feature permissions, so it never
        // carries its own permission rows.
        if (req.getParentWorkspaceId() != null) {
            Workspace parent = workspaceRepo.findById(req.getParentWorkspaceId()).orElse(null);
            if (parent == null || !parent.isBranchParent()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("title", "Parent must be an existing branch workspace"));
            }
            if (req.getAdminUserId() == null) {
                return ResponseEntity.badRequest().body(Map.of("title", "A branch needs an admin"));
            }
            String memberGuard = adminMembershipConflict(req.getAdminUserId());
            if (memberGuard != null) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("title", memberGuard));

            Workspace branch = workspaceRepo.save(Workspace.builder()
                    .code(req.getCode()).name(req.getName())
                    .kind(parent.getKind()).division(req.getDivision())
                    .dailySmsLimit(req.getDailySmsLimit())
                    .parentWorkspaceId(parent.getId()).isBranchParent(false)
                    .status("ACTIVE").build());
            assignAdmin(branch, req.getAdminUserId());
            assignDelegate(branch, req.getDelegateUserId(),
                    parentPermissions(parent).contains("DELEGATION"));
            return ResponseEntity.status(HttpStatus.CREATED).body(toDtoEnriched(branch, parentPermissions(parent)));
        }

        // ── Case 2: an umbrella + its mandatory first branch, atomically ──────
        if (req.isBranchParent()) {
            if (req.getFirstBranchName() == null || req.getFirstBranchName().isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("title", "A branch workspace must be created with at least one branch"));
            }
            if (req.getFirstBranchAdminUserId() == null) {
                return ResponseEntity.badRequest().body(Map.of("title", "The first branch needs an admin"));
            }
            String memberGuard = adminMembershipConflict(req.getFirstBranchAdminUserId());
            if (memberGuard != null) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("title", memberGuard));

            Workspace umbrella = workspaceRepo.save(Workspace.builder()
                    .code(req.getCode()).name(req.getName())
                    .kind(req.getKind() != null ? req.getKind() : "GENERIC")
                    .division(req.getDivision()).isBranchParent(true)
                    .status("ACTIVE").build());
            List<String> perms = req.getPermissions() != null ? req.getPermissions() : List.of();
            for (String perm : perms) permissionRepo.save(new WorkspacePermission(umbrella.getId(), perm));

            Workspace branch = workspaceRepo.save(Workspace.builder()
                    .code(deriveBranchCode(req.getCode(), req.getFirstBranchName()))
                    .name(req.getFirstBranchName())
                    .kind(umbrella.getKind())
                    .dailySmsLimit(req.getDailySmsLimit())
                    .parentWorkspaceId(umbrella.getId()).isBranchParent(false)
                    .status("ACTIVE").build());
            assignAdmin(branch, req.getFirstBranchAdminUserId());
            assignDelegate(branch, req.getDelegateUserId(), perms.contains("DELEGATION"));
            return ResponseEntity.status(HttpStatus.CREATED).body(toDtoEnriched(umbrella, perms));
        }

        // ── Case 3: a standalone workspace (original behaviour) ───────────────
        if (req.getAdminUserId() != null) {
            String memberGuard = adminMembershipConflict(req.getAdminUserId());
            if (memberGuard != null) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("title", memberGuard));
        }

        Workspace savedWs = workspaceRepo.save(Workspace.builder()
                .code(req.getCode()).name(req.getName())
                .kind(req.getKind() != null ? req.getKind() : "GENERIC")
                .division(req.getDivision()).dailySmsLimit(req.getDailySmsLimit())
                .status("ACTIVE").build());

        if (req.getPermissions() != null) {
            for (String perm : req.getPermissions()) {
                permissionRepo.save(new WorkspacePermission(savedWs.getId(), perm));
            }
        }

        assignAdmin(savedWs, req.getAdminUserId());

        boolean hasDelegation = req.getPermissions() != null && req.getPermissions().contains("DELEGATION");
        assignDelegate(savedWs, req.getDelegateUserId(), hasDelegation);

        List<String> savedPerms = req.getPermissions() != null ? req.getPermissions() : List.of();
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(savedWs, savedPerms));
    }

    // Enforces single membership for a workspace admin: null == ok, else the
    // reason to reject. A user already in any workspace can't be made a new
    // workspace's admin.
    private String adminMembershipConflict(UUID adminUserId) {
        if (adminUserId == null) return null;
        return memberRepo.findByUserId(adminUserId).isEmpty()
                ? null : "Cannot assign a user that already belongs to another workspace.";
    }

    private void assignAdmin(Workspace ws, UUID adminUserId) {
        if (adminUserId == null) return;
        roleRepo.findByCode("DEPT_HEAD").ifPresent(role ->
                userRepo.findById(adminUserId).ifPresent(user ->
                        memberRepo.save(WorkspaceMember.builder()
                                .workspace(ws).user(user).role(role)
                                .assignedAt(Instant.now())
                                .assignedBy(WorkspaceContext.currentUserId()).build())));
    }

    // Adds a delegate (CEO role) when the workspace has the DELEGATION feature.
    // A delegate must be an unassigned user or an existing member of THIS
    // workspace (promoted in place) — never pulled from another workspace, and
    // never the workspace's own head.
    private void assignDelegate(Workspace ws, UUID delegateUserId, boolean delegationEnabled) {
        if (!delegationEnabled || delegateUserId == null) return;
        var existingHere = memberRepo.findByWorkspaceIdAndUserId(ws.getId(), delegateUserId);
        boolean inAnotherWs = memberRepo.findByUserId(delegateUserId).stream()
                .anyMatch(m -> !m.getWorkspace().getId().equals(ws.getId()));
        if (inAnotherWs) {
            log.warn("Delegate {} belongs to another workspace — not assigned to {}", delegateUserId, ws.getId());
            return;
        }
        roleRepo.findByCode("CEO").ifPresent(ceoRole ->
                userRepo.findById(delegateUserId).ifPresent(user ->
                        existingHere.ifPresentOrElse(
                                m -> {
                                    if (!"DEPT_HEAD".equals(m.getRole().getCode())) { m.setRole(ceoRole); memberRepo.save(m); }
                                },
                                () -> memberRepo.save(WorkspaceMember.builder()
                                        .workspace(ws).user(user).role(ceoRole)
                                        .assignedAt(Instant.now())
                                        .assignedBy(WorkspaceContext.currentUserId()).build()))));
    }

    private List<String> parentPermissions(Workspace parent) {
        return permissionRepo.findByWorkspaceId(parent.getId())
                .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
    }

    // Graceful teardown of the delegate seat: every CEO member (except an
    // optional keep) is demoted to Operator so they keep workspace access
    // instead of being deleted and left with no workspace at all.
    private void demoteDelegates(UUID workspaceId, UUID exceptUserId) {
        roleRepo.findByCode("OPERATOR").ifPresent(opRole ->
                memberRepo.findByWorkspaceId(workspaceId).stream()
                        .filter(m -> "CEO".equals(m.getRole().getCode()))
                        .filter(m -> exceptUserId == null || !m.getUser().getId().equals(exceptUserId))
                        .forEach(m -> { m.setRole(opRole); memberRepo.save(m); }));
    }

    private String deriveBranchCode(String parentCode, String branchName) {
        String slug = branchName.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
        String base = parentCode + "-" + (slug.isEmpty() ? "branch" : slug);
        String code = base;
        int n = 2;
        while (workspaceRepo.existsByCode(code)) code = base + "-" + n++;
        return code;
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
    @Transactional
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
                            // Delegation turned off (or delegate cleared): the former
                            // delegate keeps workspace access, gracefully demoted to
                            // Operator rather than being removed and left orphaned.
                            demoteDelegates(id, null);
                        } else {
                            UUID delegateId = UUID.fromString(delegateIdStr);
                            // A delegate can only be an unassigned user or an existing
                            // member of THIS workspace who isn't its head — never one
                            // pulled from another workspace.
                            boolean inAnotherWs = memberRepo.findByUserId(delegateId).stream()
                                    .anyMatch(m -> !m.getWorkspace().getId().equals(id));
                            boolean isThisHead = memberRepo.findByWorkspaceIdAndUserId(id, delegateId)
                                    .map(m -> "DEPT_HEAD".equals(m.getRole().getCode())).orElse(false);
                            if (inAnotherWs || isThisHead) {
                                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("title",
                                        isThisHead ? "The workspace head cannot also be its delegate"
                                                   : "A delegate must be unassigned or already a member of this workspace"));
                            }
                            roleRepo.findByCode("CEO").ifPresent(ceoRole -> {
                                // Demote whoever held the delegate seat before (except
                                // the new pick, if they somehow already held it).
                                demoteDelegates(id, delegateId);
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
                        demoteDelegates(id, null);
                    }

                    List<String> currentPerms = permissionRepo.findByWorkspaceId(id)
                            .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
                    // Delegation ended up OFF for this workspace: finalize any campaign
                    // stranded in PENDING_CEO — it already passed head review and there's
                    // no delegate left to sign off, so it must not be left orphaned.
                    if (!currentPerms.contains("DELEGATION")) {
                        campaignService.finalizePendingDelegateApprovals(id);
                    }
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

        var userWs = memberRepo.findByUserId(req.getUserId());
        var hasWs =  userWs.size() > 0? true : false;
        
        if(hasWs){
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "User already has a workspace"));
        }

        var member = WorkspaceMember.builder()
                .workspace(wsOpt.get())
                .user(userOpt.get())
                .role(roleOpt.get())
                .assignedAt(Instant.now())
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
    @Transactional
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

    // Resolves the effective feature permissions for a workspace: a branch has
    // none of its own and inherits its umbrella's; everyone else uses theirs.
    private List<String> effectivePermissions(Workspace ws) {
        UUID source = ws.getParentWorkspaceId() != null ? ws.getParentWorkspaceId() : ws.getId();
        return permissionRepo.findByWorkspaceId(source)
                .stream().map(WorkspacePermission::getPermissionCode).collect(Collectors.toList());
    }

    private WorkspaceDto toDtoEnriched(Workspace ws, List<String> permissions) {
        // A branch always reports its umbrella's permissions regardless of what
        // the caller computed, so inheritance can never drift.
        if (ws.getParentWorkspaceId() != null) {
            permissions = effectivePermissions(ws);
        }
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

        String parentName = ws.getParentWorkspaceId() != null
                ? workspaceRepo.findById(ws.getParentWorkspaceId()).map(Workspace::getName).orElse(null)
                : null;
        int branchCount = ws.isBranchParent()
                ? workspaceRepo.findByParentWorkspaceId(ws.getId()).size() : 0;

        return new WorkspaceDto(ws.getId(), ws.getCode(), ws.getName(),
                ws.getKind(), ws.getDivision(), ws.getStatus(), ws.getSenderMask(),
                ws.getDailySmsLimit(), permissions, (int) memberCount, adminName, adminUserId,
                delegateName, delegateUserId,
                ws.getParentWorkspaceId(), parentName, ws.isBranchParent(), branchCount);
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
        private UUID         parentWorkspaceId;
        private String       parentName;
        private boolean      isBranchParent;
        private int          branchCount;
    }

    @Data
    public static class CreateWorkspaceRequest {
        @NotBlank private String code;
        @NotBlank private String name;
        private String kind;
        private String division;
        private Integer dailySmsLimit;
        private UUID adminUserId;

        private UUID delegateUserId;
        private List<String> permissions;
        // Branch hierarchy (see V029). parentWorkspaceId set ⇒ this is a branch
        // under that parent. isBranchParent ⇒ this workspace is an umbrella that
        // owns branches and holds no data of its own; when true, firstBranch*
        // create its mandatory first branch in the same request.
        private UUID parentWorkspaceId;
        private boolean isBranchParent;
        private String firstBranchName;
        private UUID firstBranchAdminUserId;
    }

    @Data
    public static class AddMemberRequest {
        private UUID userId;
        private UUID roleId;
    }
}
