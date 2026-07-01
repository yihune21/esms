package et.com.cog.esms.core.workspace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleRepository roleRepo;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<RoleDto>> listRoles() {
        List<RoleDto> roles = roleRepo.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(roles);
    }

    private RoleDto toDto(Role role) {
        return new RoleDto(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getScope(),
                role.isImmutable(),
                role.getStatus()
        );
    }

    @Data
    @AllArgsConstructor
    public static class RoleDto {
        private UUID id;
        private String code;
        private String name;
        private String scope;
        private boolean immutable;
        private String status;
    }
}
