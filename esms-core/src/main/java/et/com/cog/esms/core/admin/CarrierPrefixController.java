package et.com.cog.esms.core.admin;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/carrier-prefixes")
@RequiredArgsConstructor
public class CarrierPrefixController {
    private final CarrierPrefixRepository repo;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<CarrierPrefix>> list() {
        return ResponseEntity.ok(repo.findAll());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<CarrierPrefix> create(@RequestBody CarrierPrefix req) {
        req.setId(null);
        return ResponseEntity.ok(repo.save(req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
    
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<CarrierPrefix> update(@PathVariable UUID id, @RequestBody CarrierPrefix req) {
        return repo.findById(id).map(c -> {
            if (req.getPrefix() != null) c.setPrefix(req.getPrefix());
            if (req.getCarrier() != null) c.setCarrier(req.getCarrier());
            c.setActive(req.isActive());
            return ResponseEntity.ok(repo.save(c));
        }).orElse(ResponseEntity.notFound().build());
    }
}
