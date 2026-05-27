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

/**
 * Individual contact CRUD.
 * Reference: LLD §4.3
 */
@Slf4j
@RestController
@RequestMapping("/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactRepository contactRepo;

    // ── GET /contacts ────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<List<ContactDto>> list() {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<ContactDto> result = contactRepo.findByWorkspaceIdOrderByCreatedAtDesc(wsId)
                .stream().map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // ── POST /contacts ───────────────────────────────────────────
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
                .optOut(false)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(contactRepo.save(contact)));
    }

    // ── GET /contacts/{id} ───────────────────────────────────────
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<ContactDto> get(@PathVariable UUID id) {
        return contactRepo.findById(id)
                .map(c -> ResponseEntity.ok(toDto(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH /contacts/{id} ─────────────────────────────────────
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> updates) {
        return contactRepo.findById(id)
                .map(c -> {
                    if (updates.containsKey("name"))    c.setName((String) updates.get("name"));
                    if (updates.containsKey("branch"))  c.setBranch((String) updates.get("branch"));
                    if (updates.containsKey("optOut"))  c.setOptOut(Boolean.TRUE.equals(updates.get("optOut")));
                    return ResponseEntity.ok(toDto(contactRepo.save(c)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /contacts/{id} ────────────────────────────────────
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!contactRepo.existsById(id)) return ResponseEntity.notFound().build();
        contactRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ContactDto toDto(Contact c) {
        return new ContactDto(c.getId(), c.getWorkspaceId(), c.getName(),
                c.getPhoneE164(), c.getBranch(), c.isOptOut(), c.getCreatedAt());
    }

    // ── DTOs ─────────────────────────────────────────────────────

    @Data @AllArgsConstructor
    public static class ContactDto {
        private UUID id;
        private UUID workspaceId;
        private String name;
        private String phoneE164;
        private String branch;
        private boolean optOut;
        private Instant createdAt;
    }

    @Data
    public static class CreateContactRequest {
        @NotBlank private String name;
        @NotBlank
        @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "Phone must be E.164 format, e.g. +251911000000")
        private String phoneE164;
        private String branch;
    }
}
