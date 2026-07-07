package et.com.cog.esms.core.identity;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import et.com.cog.esms.core.audit.AuditService;
import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final WorkspaceMemberRepository memberRepo;
    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('WORKSPACE_MEMBER_ADD')")
    public ResponseEntity<List<UserDto>> list(
            @RequestParam(required = false) String status) {
        List<AppUser> users = status != null
                ? userRepo.findByStatus(status)
                : userRepo.findAll();
        return ResponseEntity.ok(users.stream().map(this::toDto).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('WORKSPACE_MEMBER_ADD')")
    public ResponseEntity<UserDto> get(@PathVariable UUID id) {
        return userRepo.findById(id)
                .map(u -> ResponseEntity.ok(toDto(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('WORKSPACE_MEMBER_ADD')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateUserRequest req) {
        if (userRepo.existsByUsername(req.getUsername())) {
            auditService.log(WorkspaceContext.currentWorkspaceId(), "ADMIN", "WARN",
                    "USER_CREATE_DUPLICATE_USERNAME", "User", null);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "Username already exists"));
        }

        AppUser user = AppUser.builder()
                .username(req.getUsername())
                .displayName(req.getDisplayName())
                .email(req.getEmail())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .status("ACTIVE")
                .failedLogins((short) 0)
                .build();

        AppUser saved = userRepo.save(user);

        auditService.log(WorkspaceContext.currentWorkspaceId(), "ADMIN", "INFO",
                "USER_CREATED", "User", saved.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('WORKSPACE_MEMBER_ADD')")
    public ResponseEntity<?> update(@PathVariable UUID id,
                                     @RequestBody Map<String, Object> updates) {
        return userRepo.findById(id)
                .map(u -> {
                    if (updates.containsKey("displayName")) u.setDisplayName((String) updates.get("displayName"));
                    if (updates.containsKey("email")) u.setEmail((String) updates.get("email"));

                    boolean passwordChanged = updates.containsKey("password");
                    if (passwordChanged) {
                        u.setPasswordHash(passwordEncoder.encode((String) updates.get("password")));
                    }

                    AppUser saved = userRepo.save(u);

                    auditService.log(WorkspaceContext.currentWorkspaceId(), "ADMIN", "INFO",
                            "USER_UPDATED", "User", saved.getId());
                    if (passwordChanged) {
                        auditService.log(WorkspaceContext.currentWorkspaceId(), "ADMIN", "WARN",
                                "USER_PASSWORD_RESET_BY_ADMIN", "User", saved.getId());
                    }

                    return ResponseEntity.ok(toDto(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('WORKSPACE_MEMBER_ADD')")
    public ResponseEntity<?> deactivate(@PathVariable UUID id) {
        return userRepo.findById(id)
                .map(u -> {
                    if ("DISABLED".equals(u.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .<UserDto>body(null);
                    }
                    u.setStatus("DISABLED");
                    AppUser saved = userRepo.save(u);

                    auditService.log(WorkspaceContext.currentWorkspaceId(), "ADMIN", "WARN",
                            "USER_DEACTIVATED", "User", saved.getId());

                    return ResponseEntity.ok(toDto(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('WORKSPACE_MEMBER_ADD')")
    public ResponseEntity<?> activate(@PathVariable UUID id) {
        return userRepo.findById(id)
                .map(u -> {
                    if ("ACTIVE".equals(u.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .<UserDto>body(null);
                    }
                    u.setStatus("ACTIVE");
                    u.setFailedLogins((short) 0);
                    u.setLockedUntil(null);
                    AppUser saved = userRepo.save(u);

                    auditService.log(WorkspaceContext.currentWorkspaceId(), "ADMIN", "INFO",
                            "USER_ACTIVATED", "User", saved.getId());

                    return ResponseEntity.ok(toDto(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/memberships")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('WORKSPACE_MEMBER_ADD')")
    public ResponseEntity<List<Map<String, Object>>> getMemberships(@PathVariable UUID id) {
        var memberships = memberRepo.findByUserId(id);
        var result = memberships.stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("workspaceId", m.getWorkspace().getId());
            map.put("workspaceName", m.getWorkspace().getName());
            map.put("division", m.getWorkspace().getDivision());
            map.put("role", m.getRole().getCode());
            map.put("assignedAt", m.getAssignedAt());
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }


    private UserDto toDto(AppUser u) {
        List<MembershipDto> memberships = memberRepo.findByUserId(u.getId()).stream()
                .map(m -> new MembershipDto(
                        m.getWorkspace().getId(),
                        m.getWorkspace().getName(),
                        m.getWorkspace().getDivision(),
                        m.getRole().getCode(),
                        m.getAssignedAt()
                ))
                .collect(Collectors.toList());

        String primaryRole      = memberships.isEmpty() ? null : memberships.get(0).getRole();
        String primaryWorkspace = memberships.isEmpty() ? null : memberships.get(0).getWorkspaceName();
        String division         = memberships.isEmpty() ? null : memberships.get(0).getDivision();

        return new UserDto(
                u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(),
                u.getStatus(), u.getLastLoginAt(), u.getCreatedAt(), u.getUpdatedAt(),
                primaryRole, primaryWorkspace, division, memberships
        );
    }


    @Data @AllArgsConstructor
    public static class UserDto {
        private UUID    id;
        private String  username;
        private String  displayName;
        private String  email;
        private String  status;
        private Instant lastLoginAt;
        private Instant createdAt;
        private Instant updatedAt;
        private String  primaryRole;
        private String  primaryWorkspace;
        private String  division;
        private List<MembershipDto> memberships;
    }

    @Data @AllArgsConstructor
    public static class MembershipDto {
        private UUID    workspaceId;
        private String  workspaceName;
        private String  division;
        private String  role;
        private Instant assignedAt;
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank private String username;
        @NotBlank private String displayName;
        private String email;
        @NotBlank private String password;
    }
}
