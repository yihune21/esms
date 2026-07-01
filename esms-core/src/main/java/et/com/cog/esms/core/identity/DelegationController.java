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


@RestController
@RequestMapping("/delegations")
@RequiredArgsConstructor
public class DelegationController {

    private final DelegationRepository delegationRepo;
    private final UserRepository       userRepo;


    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_DELEGATE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateDelegationRequest req) {
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
        @NotNull
        private UUID    toUserId;
 
        private UUID    workspaceId;
        private Instant startsAt;

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
