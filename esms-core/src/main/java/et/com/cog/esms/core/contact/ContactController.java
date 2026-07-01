package et.com.cog.esms.core.contact;

import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@RestController
@RequestMapping("/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactRepository contactRepo;

    @GetMapping
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<List<ContactDto>> list(
            @RequestParam(required = false) String status) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<Contact> contacts = status != null
                ? contactRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(wsId, status)
                : contactRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(wsId, "ACTIVE");
        List<ContactDto> result = contacts.stream().map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateContactRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();

        if (contactRepo.existsByWorkspaceIdAndPhoneE164(wsId, req.getPhoneE164())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "Phone number already exists in this workspace"));
        }

        Contact contact = Contact.builder()
                .workspaceId(wsId)
                .name(req.getName())
                .phoneE164(req.getPhoneE164())
                .branch(req.getBranch())
                .extra(req.getExtra() != null ? req.getExtra() : new HashMap<>())
                .optOut(false)
                .status("ACTIVE")
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(contactRepo.save(contact)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<ContactDto> get(@PathVariable UUID id) {
        return contactRepo.findById(id)
                .map(c -> ResponseEntity.ok(toDto(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_UPDATE')")
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> updates) {
        return contactRepo.findById(id)
                .map(c -> {
                    if (updates.containsKey("name"))    c.setName((String) updates.get("name"));
                    if (updates.containsKey("branch"))  c.setBranch((String) updates.get("branch"));
                    if (updates.containsKey("optOut"))  c.setOptOut(Boolean.TRUE.equals(updates.get("optOut")));
                    if (updates.containsKey("extra")) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> newExtra = (Map<String, String>) updates.get("extra");
                        c.setExtra(newExtra);
                    }
                    return ResponseEntity.ok(toDto(contactRepo.save(c)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('CONTACT_DELETE')")
    @Transactional
    public ResponseEntity<ContactDto> deactivate(@PathVariable UUID id) {
        return contactRepo.findById(id).map(c -> {
            c.setStatus("INACTIVE");
            return ResponseEntity.ok(toDto(contactRepo.save(c)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('CONTACT_UPDATE')")
    @Transactional
    public ResponseEntity<ContactDto> activate(@PathVariable UUID id) {
        return contactRepo.findById(id).map(c -> {
            c.setStatus("ACTIVE");
            return ResponseEntity.ok(toDto(contactRepo.save(c)));
        }).orElse(ResponseEntity.notFound().build());
    }


    private ContactDto toDto(Contact c) {
        return new ContactDto(c.getId(), c.getWorkspaceId(), c.getName(),
                c.getPhoneE164(), c.getBranch(), c.getExtra(), c.getUploadId(),
                c.isOptOut(), c.getStatus(), c.getCreatedAt());
    }


    @Data @AllArgsConstructor
    public static class ContactDto {
        private UUID id;
        private UUID workspaceId;
        private String name;
        private String phoneE164;
        private String branch;
        private Map<String, String> extra;
        private UUID uploadId;
        private boolean optOut;
        private String status;
        private Instant createdAt;
    }

    @Data
    public static class CreateContactRequest {
        @NotBlank private String name;
        @NotBlank
        @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "Phone must be E.164 format, e.g. +251911000000")
        private String phoneE164;
        private String branch;
        private Map<String, String> extra;
    }
}
