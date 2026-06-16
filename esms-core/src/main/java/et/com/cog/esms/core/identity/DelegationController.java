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
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_DELEGATE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateDelegationRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        UUID fromUserId = WorkspaceContext.currentUserId();

        if (req.getToUserId().equals(fromUserId)) {
            return ResponseEntity.badRequest().body(Map.of("title", "Cannot delegate to yourself"));
        }

        if (!userRepo.existsById(req.getToUserId())) {
            return ResponseEntity.badRequest().body(Map.of("title", "Delegate user not found"));
        }

        Instant startsAt = req.getStartsAt() != null ? req.getStartsAt() : Instant.now();
        Instant endsAt = req.getEndsAt();

        if (endsAt.isBefore(startsAt)) {
            return ResponseEntity.badRequest().body(Map.of("title", "EndsAt must be after StartsAt"));
        }

        // Check if delegation exceeds 30 days
        long days = ChronoUnit.DAYS.between(startsAt, endsAt);
        if (days > 30) {
            return ResponseEntity.badRequest().body(Map.of("title", "Delegation cannot exceed 30 days"));
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
     * List all delegations for the current workspace.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN_DELEGATE')")
    public ResponseEntity<List<DelegationDto>> list(
            @RequestParam(required = false) Boolean activeOnly) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<Delegation> list = delegationRepo.findByWorkspaceId(wsId);

        if (Boolean.TRUE.equals(activeOnly)) {
            Instant now = Instant.now();
            list = list.stream()
                    .filter(d -> !d.isRevoked() && d.getStartsAt().isBefore(now) && d.getEndsAt().isAfter(now))
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

    @Data
    public static class CreateDelegationRequest {
        @NotNull private UUID toUserId;
        private Instant startsAt;
        @NotNull @Future private Instant endsAt;
        private String reason;
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
