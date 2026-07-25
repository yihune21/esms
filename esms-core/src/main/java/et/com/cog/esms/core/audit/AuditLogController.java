package et.com.cog.esms.core.audit;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepo;
    private final AuditChainVerifier chainVerifier;

    /**
     * Recomputes the hash chain and reports any break. Restricted to platform
     * admins: it is an integrity check on the record of everyone's actions,
     * and a workspace admin should not be able to probe it.
     */
    @GetMapping("/verify")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<AuditChainVerifier.Result> verifyChain(
            @RequestParam(defaultValue = "0") long maxRows) {
        return ResponseEntity.ok(chainVerifier.verify(maxRows));
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('WORKSPACE_VIEW')")
    public ResponseEntity<Page<AuditLogDto>> list(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Instant startDate,
            @RequestParam(required = false) Instant endDate,
            Pageable pageable) {

        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (severity != null && !severity.isBlank() ) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (workspaceId != null) {
                predicates.add(cb.equal(root.get("workspaceId"), workspaceId));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("action")), like),
                        cb.like(cb.lower(root.get("actorUsername")), like),
                        cb.like(cb.lower(root.get("category")), like)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditLogDto> page = auditLogRepo.findAll(spec, pageable).map(this::toDto);
        return ResponseEntity.ok(page);
    }

    private AuditLogDto toDto(AuditLog a) {
        return new AuditLogDto(a.getId(), a.getWorkspaceId(), a.getActorUserId(),
                a.getActorUsername(), a.getCategory(), a.getSeverity(), a.getAction(),
                a.getEntityType(), a.getEntityId(), a.getIpAddress(), a.getCreatedAt());
    }

    @Data
    public static class AuditLogDto {
        private final UUID id;
        private final UUID workspaceId;
        private final UUID actorUserId;
        private final String actorUsername;
        private final String category;
        private final String severity;
        private final String action; 
        private final String entityType; 
        private final UUID entityId;
        private final String ipAddress;
        private final Instant createdAt; 
    }
}
