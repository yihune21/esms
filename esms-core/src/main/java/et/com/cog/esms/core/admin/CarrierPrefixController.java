package et.com.cog.esms.core.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import et.com.cog.esms.core.audit.AuditService;
import et.com.cog.esms.core.security.WorkspaceContext;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/carrier-prefixes")
@RequiredArgsConstructor
public class CarrierPrefixController {
    private final CarrierPrefixRepository repo;
    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<CarrierPrefix>> list() {
        return ResponseEntity.ok(repo.findAll());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<CarrierPrefix> create(@RequestBody CarrierPrefix req) {
        req.setId(null);
        CarrierPrefix saved = repo.save(req);

        auditService.log(WorkspaceContext.currentWorkspaceId(), "ADMIN", "INFO",
                "CARRIER_PREFIX_CREATED", "CarrierPrefix", saved.getId());

        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        repo.deleteById(id);

        auditService.log(WorkspaceContext.currentWorkspaceId(), "ADMIN", "WARN",
                "CARRIER_PREFIX_DELETED", "CarrierPrefix", id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<CarrierPrefix> update(@PathVariable UUID id, @RequestBody CarrierPrefix req) {
        return repo.findById(id).map(c -> {
            if (req.getPrefix() != null) c.setPrefix(req.getPrefix());
            if (req.getCarrier() != null) c.setCarrier(req.getCarrier());
            c.setActive(req.isActive());
            CarrierPrefix saved = repo.save(c);

            auditService.log(WorkspaceContext.currentWorkspaceId(), "ADMIN", "INFO",
                    "CARRIER_PREFIX_UPDATED", "CarrierPrefix", saved.getId());

            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }
}
