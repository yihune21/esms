package et.com.cog.esms.core.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SystemSettingController {
    private final SystemSettingRepository repo;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> getAll() {
        return ResponseEntity.ok(repo.findAll().stream()
                .collect(Collectors.toMap(SystemSetting::getKey, SystemSetting::getValue)));
    }

    @PatchMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> updateAll(@RequestBody Map<String, String> updates) {
        List<SystemSetting> settings = repo.findAll();
        for (SystemSetting s : settings) {
            if (updates.containsKey(s.getKey())) {
                s.setValue(updates.get(s.getKey()));
                s.setUpdatedAt(Instant.now());
                repo.save(s);
            }
        }
        return ResponseEntity.ok(repo.findAll().stream()
                .collect(Collectors.toMap(SystemSetting::getKey, SystemSetting::getValue)));
    }
}
