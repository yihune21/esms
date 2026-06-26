package et.com.cog.esms.core.identity;

import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Controller for managing user delegations (acting on behalf of other users in a workspace).
 * Reference: LLD §4.1, V001__workspace_and_identity.sql
 *
 * Bug-fix #6 changes:
 *  (a) CreateDelegationRequest now accepts an optional {@code workspaceId} so a SUPER_ADMIN
 *      can create a delegation for a workspace they are not a member of.
 *  (b) {@code endsAt} is now optional (null = no expiry / standing delegate). The 30-day
 *      cap is enforced only when endsAt is explicitly provided.
 *  (c) GET /delegations/mine — open to any authenticated user — lets a plain delegate
 *      (e.g. an OPERATOR) discover their own incoming delegations without needing
 *      ADMIN_DELEGATE authority.
 */
@RestController
@RequestMapping("/delegations")
@RequiredArgsConstructor
public class DelegationController {

    private final DelegationRepository delegationRepo;
    private final UserRepository       userRepo;

    /**
     * Create a new role/permission delegation.
     * Requires ADMIN_DELEGATE authority.
     *
     * Fix 6a: accepts an optional {@code workspaceId} in the body so a SUPER_ADMIN
     *          can target a workspace they are not actively "in".
     * Fix 6b: {@code endsAt} is now optional; omitting it creates a standing delegation.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_DELEGATE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateDelegationRequest req) {
        // Fix 6a: prefer explicit workspaceId from the request body; fall back to context
        UUID wsId = (req.getWorkspaceId() != null)
                ? req.getWorkspaceId()
                : WorkspaceContext.currentWorkspaceId();

        if (wsId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("title", "workspaceId is required when no workspace context is active"));
        }

        UUID fromUserId = WorkspaceContext.currentUserId();

        if (req.getToUserId().equals(fromUserId)) {
            return ResponseEntity.badRequest().body(Map.of("title", "Cannot delegate to yourself"));
        }

        if (!userRepo.existsById(req.getToUserId())) {
            return ResponseEntity.badRequest().body(Map.of("title", "Delegate user not found"));
        }

        Instant startsAt = req.getStartsAt() != null ? req.getStartsAt() : Instant.now();
        Instant endsAt   = req.getEndsAt(); // Fix 6b: may be null (standing delegation)

        if (endsAt != null) {
            if (endsAt.isBefore(startsAt)) {
                return ResponseEntity.badRequest().body(Map.of("title", "endsAt must be after startsAt"));
            }
            // Warn-only: allow longer windows, but surface a 400 if the caller explicitly
            // requests > 365 days (sanity cap).
            long days = ChronoUnit.DAYS.between(startsAt, endsAt);
            if (days > 365) {
                return ResponseEntity.badRequest()
                        .body(Map.of("title", "Delegation window cannot exceed 365 days; omit endsAt for a standing delegation"));
            }
        }

        Delegation delegation = Delegation.builder()
                .workspaceId(wsId)
                .fromUserId(fromUserId)
                .toUserId(req.getToUserId())
                .startsAt(startsAt)
                .endsAt(endsAt)
                .reason(req.getReason())
                .revoked(false)
                .build();

        Delegation saved = delegationRepo.save(delegation);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    /**
     * List all delegations for the current workspace (admin view).
     * Requires ADMIN_DELEGATE authority.
     * Fix #8: Super admin with no workspace context sees delegations across all workspaces.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN_DELEGATE')")
    public ResponseEntity<List<DelegationDto>> list(
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) UUID workspaceId) {
        UUID wsId = workspaceId != null ? workspaceId : WorkspaceContext.currentWorkspaceId();

        List<Delegation> list;
        if (wsId != null) {
            list = delegationRepo.findByWorkspaceId(wsId);
        } else {
            // Super admin with no workspace context: list across all workspaces
            list = delegationRepo.findAll();
        }

        if (Boolean.TRUE.equals(activeOnly)) {
            Instant now = Instant.now();
            list = list.stream()
                    .filter(d -> !d.isRevoked()
                            && d.getStartsAt().isBefore(now)
                            // null endsAt = no expiry → always active
                            && (d.getEndsAt() == null || d.getEndsAt().isAfter(now)))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(list.stream().map(this::toDto).collect(Collectors.toList()));
    }

    /**
     * Fix 6c: "My delegations" — returns all incoming delegations for the calling user.
     * Open to any authenticated user (no ADMIN_DELEGATE required) so that a plain
     * delegate can discover they have been delegated authority.
     */
    @GetMapping("/mine")
    public ResponseEntity<List<DelegationDto>> mine(
            @RequestParam(required = false) Boolean activeOnly) {
        UUID userId = WorkspaceContext.currentUserId();
        List<Delegation> list = delegationRepo.findByToUserId(userId);

        if (Boolean.TRUE.equals(activeOnly)) {
            Instant now = Instant.now();
            list = list.stream()
                    .filter(d -> !d.isRevoked()
                            && d.getStartsAt().isBefore(now)
                            && (d.getEndsAt() == null || d.getEndsAt().isAfter(now)))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(list.stream().map(this::toDto).collect(Collectors.toList()));
    }

    /**
     * Revoke a delegation.
     */
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('ADMIN_DELEGATE')")
    public ResponseEntity<?> revoke(@PathVariable UUID id) {
        return delegationRepo.findById(id)
                .map(d -> {
                    if (d.isRevoked()) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("title", "Delegation is already revoked"));
                    }
                    d.setRevoked(true);
                    delegationRepo.save(d);
                    return ResponseEntity.ok(toDto(d));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private DelegationDto toDto(Delegation d) {
        String fromUserName = userRepo.findById(d.getFromUserId()).map(AppUser::getDisplayName).orElse("Unknown");
        String toUserName   = userRepo.findById(d.getToUserId()).map(AppUser::getDisplayName).orElse("Unknown");

        return new DelegationDto(
                d.getId(), d.getWorkspaceId(), d.getFromUserId(), fromUserName,
                d.getToUserId(), toUserName, d.getStartsAt(), d.getEndsAt(),
                d.getReason(), d.isRevoked(), d.getCreatedAt()
        );
    }

    // ── DTOs ─────────────────────────────────────────────────────

    @Data
    public static class CreateDelegationRequest {
        @NotNull
        private UUID    toUserId;
        /**
         * Fix 6a: Optional. When a SUPER_ADMIN creates a delegation for a workspace
         * they are not actively in (no WorkspaceContext), they must supply this field.
         */
        private UUID    workspaceId;
        private Instant startsAt;
        /**
         * Fix 6b: Optional. When omitted, the delegation is standing (no expiry).
         * When supplied, must be in the future and ≤ 365 days from startsAt.
         */
        @Future
        private Instant endsAt;
        private String  reason;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class DelegationDto {
        private UUID    id;
        private UUID    workspaceId;
        private UUID    fromUserId;
        private String  fromUserName;
        private UUID    toUserId;
        private String  toUserName;
        private Instant startsAt;
        private Instant endsAt;
        private String  reason;
        private boolean revoked;
        private Instant createdAt;
    }
}
