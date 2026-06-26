package et.com.cog.esms.core.template;

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
 * SMS Template REST controller.
 * Lifecycle: DRAFT → APPROVED → RETIRED.
 *
 * A template can optionally carry its own recipients:
 *   • recipientGroupId — link to an existing contact group.
 *   • Inline recipients (POST/DELETE /templates/{id}/recipients) — fixed phone numbers.
 *
 * When a campaign is created from a template, esms-sender merges both recipient sources.
 *
 * Reference: LLD §4.4
 */
@Slf4j
@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateRepository            templateRepo;
    private final TemplateRecipientRepository   recipientRepo;
    private final et.com.cog.esms.core.identity.UserRepository userRepo;

    // ── GET /templates ───────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAuthority('TEMPLATE_VIEW')")
    public ResponseEntity<List<TemplateDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID workspaceId) {
        boolean isSuperAdmin = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));

        List<Template> templates;
        if (isSuperAdmin && workspaceId == null) {
            // Super admin with no specific workspace: see all templates across all departments
            templates = status != null
                    ? templateRepo.findByStatusOrderByCreatedAtDesc(status)
                    : templateRepo.findAllByOrderByCreatedAtDesc();
        } else {
            UUID wsId = workspaceId != null ? workspaceId : WorkspaceContext.currentWorkspaceId();
            templates = status != null
                    ? templateRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(wsId, status)
                    : templateRepo.findByWorkspaceIdOrderByCreatedAtDesc(wsId);
        }
        return ResponseEntity.ok(templates.stream().map(this::toDto).collect(Collectors.toList()));
    }

    // ── POST /templates ──────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasAuthority('TEMPLATE_CREATE')")
    @Transactional
    public ResponseEntity<?> create(@Valid @RequestBody CreateTemplateRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();

        if (templateRepo.existsByWorkspaceIdAndName(wsId, req.getName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "A template with that name already exists in this workspace"));
        }

        Template t = Template.builder()
                .workspaceId(wsId)
                .name(req.getName())
                .description(req.getDescription())
                .body(req.getBody())
                .encoding(req.getEncoding() != null ? req.getEncoding() : "GSM7")
                .variables(req.getVariables() != null ? req.getVariables() : List.of())
                .sender(req.getSender())
                .recipientGroupId(req.getRecipientGroupId())
                .status("DRAFT")
                .createdBy(WorkspaceContext.currentUserId())
                .build();

        Template saved = templateRepo.save(t);

        // Persist inline recipients if provided
        if (req.getRecipients() != null && !req.getRecipients().isEmpty()) {
            persistRecipients(saved.getId(), req.getRecipients());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    // ── GET /templates/{id} ──────────────────────────────────────
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_VIEW')")
    public ResponseEntity<TemplateDto> get(@PathVariable UUID id) {
        return templateRepo.findById(id)
                .map(t -> ResponseEntity.ok(toDto(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH /templates/{id} ────────────────────────────────────
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_CREATE')")
    @Transactional
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> updates) {
        return templateRepo.findById(id)
                .map(t -> {
                    if (!"DRAFT".equals(t.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .<TemplateDto>body(null);
                    }
                    if (updates.containsKey("name"))             t.setName((String) updates.get("name"));
                    if (updates.containsKey("description"))      t.setDescription((String) updates.get("description"));
                    if (updates.containsKey("body"))             t.setBody((String) updates.get("body"));
                    if (updates.containsKey("encoding"))         t.setEncoding((String) updates.get("encoding"));
                    if (updates.containsKey("sender"))           t.setSender((String) updates.get("sender"));
                    if (updates.containsKey("recipientGroupId")) {
                        String raw = (String) updates.get("recipientGroupId");
                        t.setRecipientGroupId(raw != null ? UUID.fromString(raw) : null);
                    }
                    if (updates.containsKey("variables")) {
                        @SuppressWarnings("unchecked")
                        List<String> vars = (List<String>) updates.get("variables");
                        t.setVariables(vars);
                    }
                    return ResponseEntity.ok(toDto(templateRepo.save(t)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── POST /templates/{id}/approve ─────────────────────────────
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('TEMPLATE_APPROVE')")
    public ResponseEntity<?> approve(@PathVariable UUID id) {
        return templateRepo.findById(id)
                .map(t -> {
                    if (!"DRAFT".equals(t.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("title", "Only DRAFT templates can be approved"));
                    }
                    UUID actorId = WorkspaceContext.currentUserId();
                    // Fix #1: DEPT_HEAD and SUPER_ADMIN may approve their own template
                    boolean isSuperAdmin = org.springframework.security.core.context.SecurityContextHolder
                            .getContext().getAuthentication().getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
                    boolean isDeptHead = org.springframework.security.core.context.SecurityContextHolder
                            .getContext().getAuthentication().getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_DEPT_HEAD"));
                    if (t.getCreatedBy().equals(actorId) && !isSuperAdmin && !isDeptHead) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("title", "The creator cannot approve their own template"));
                    }
                    t.setStatus("APPROVED");
                    t.setApprovedBy(actorId);
                    t.setApprovedAt(Instant.now());
                    return ResponseEntity.ok(toDto(templateRepo.save(t)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── POST /templates/{id}/reject ──────────────────────────────
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('TEMPLATE_APPROVE')")
    public ResponseEntity<?> reject(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        return templateRepo.findById(id)
                .map(t -> {
                    if (!"DRAFT".equals(t.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("title", "Only DRAFT templates can be rejected"));
                    }
                    t.setStatus("REJECTED");
                    t.setRejectionReason(payload.get("rejectionReason"));
                    return ResponseEntity.ok(toDto(templateRepo.save(t)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── POST /templates/{id}/retire ──────────────────────────────
    @PostMapping("/{id}/retire")
    @PreAuthorize("hasAuthority('TEMPLATE_APPROVE')")
    public ResponseEntity<?> retire(@PathVariable UUID id) {
        return templateRepo.findById(id)
                .map(t -> {
                    if ("RETIRED".equals(t.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("title", "Template is already retired"));
                    }
                    t.setStatus("RETIRED");
                    return ResponseEntity.ok(toDto(templateRepo.save(t)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── POST /templates/{id}/reactivate ──────────────────────────
    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('TEMPLATE_APPROVE') or hasAuthority('TEMPLATE_CREATE')")
    public ResponseEntity<?> reactivate(@PathVariable UUID id) {
        return templateRepo.findById(id)
                .map(t -> {
                    if (!"RETIRED".equals(t.getStatus()) && !"REJECTED".equals(t.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("title", "Only RETIRED or REJECTED templates can be reactivated"));
                    }
                    t.setStatus("DRAFT");
                    t.setRejectionReason(null);
                    return ResponseEntity.ok(toDto(templateRepo.save(t)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── GET /templates/{id}/recipients ───────────────────────────
    @GetMapping("/{id}/recipients")
    @PreAuthorize("hasAuthority('TEMPLATE_VIEW')")
    public ResponseEntity<List<RecipientDto>> listRecipients(@PathVariable UUID id) {
        if (!templateRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<RecipientDto> list = recipientRepo.findByTemplateId(id)
                .stream()
                .map(r -> new RecipientDto(r.getId(), r.getPhoneE164(), r.getName()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    // ── POST /templates/{id}/recipients ──────────────────────────
    /**
     * Add one or more inline recipients to a template.
     * {@code phoneE164} is required for each entry; duplicates are silently ignored.
     * Only allowed while the template is in DRAFT status.
     */
    @PostMapping("/{id}/recipients")
    @PreAuthorize("hasAuthority('TEMPLATE_CREATE')")
    @Transactional
    public ResponseEntity<?> addRecipients(@PathVariable UUID id,
                                           @Valid @RequestBody List<@Valid RecipientRequest> recipients) {
        return templateRepo.findById(id)
                .map(t -> {
                    if (!"DRAFT".equals(t.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("title", "Recipients can only be added to DRAFT templates"));
                    }
                    persistRecipients(id, recipients);
                    List<RecipientDto> list = recipientRepo.findByTemplateId(id)
                            .stream()
                            .map(r -> new RecipientDto(r.getId(), r.getPhoneE164(), r.getName()))
                            .collect(Collectors.toList());
                    return ResponseEntity.status(HttpStatus.CREATED).body(list);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /templates/{id}/recipients/{phone} ─────────────────
    /**
     * Remove a single recipient by phone number from the template.
     * Only allowed while the template is in DRAFT status.
     */
    @DeleteMapping("/{id}/recipients/{phone}")
    @PreAuthorize("hasAuthority('TEMPLATE_CREATE')")
    @Transactional
    public ResponseEntity<?> removeRecipient(@PathVariable UUID id,
                                             @PathVariable String phone) {
        return templateRepo.findById(id)
                .map(t -> {
                    if (!"DRAFT".equals(t.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("title", "Recipients can only be removed from DRAFT templates"));
                    }
                    recipientRepo.deleteByTemplateIdAndPhoneE164(id, phone);
                    return ResponseEntity.noContent().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void persistRecipients(UUID templateId, List<RecipientRequest> requests) {
        for (RecipientRequest req : requests) {
            if (!recipientRepo.existsByTemplateIdAndPhoneE164(templateId, req.getPhoneE164())) {
                recipientRepo.save(TemplateRecipient.builder()
                        .templateId(templateId)
                        .phoneE164(req.getPhoneE164())
                        .name(req.getName())
                        .build());
            }
        }
    }

    private TemplateDto toDto(Template t) {
        String creatorName = userRepo.findById(t.getCreatedBy())
                .map(et.com.cog.esms.core.identity.AppUser::getDisplayName).orElse(null);
        String approverName = t.getApprovedBy() != null ? userRepo.findById(t.getApprovedBy())
                .map(et.com.cog.esms.core.identity.AppUser::getDisplayName).orElse(null) : null;

        List<RecipientDto> recipients = recipientRepo.findByTemplateId(t.getId())
                .stream()
                .map(r -> new RecipientDto(r.getId(), r.getPhoneE164(), r.getName()))
                .collect(Collectors.toList());

        long recipientCount = recipients.size();

        return new TemplateDto(
                t.getId(), t.getWorkspaceId(), t.getName(), t.getDescription(),
                t.getBody(), t.getEncoding(), t.getStatus(), t.getRejectionReason(),
                t.getVariables(), t.getSender(),
                t.getRecipientGroupId(), recipients, recipientCount,
                t.getApprovedBy(), approverName, t.getApprovedAt(),
                t.getCreatedBy(), creatorName, t.getCreatedAt()
        );
    }

    // ── DTOs ─────────────────────────────────────────────────────

    @Data @AllArgsConstructor
    public static class TemplateDto {
        private UUID            id;
        private UUID            workspaceId;
        private String          name;
        /** Optional description of the template's purpose. */
        private String          description;
        private String          body;
        private String          encoding;
        private String          status;
        private String          rejectionReason;
        private List<String>    variables;
        private String          sender;
        /** Optional contact group ID used as recipient list. */
        private UUID            recipientGroupId;
        /** Inline recipients attached directly to this template. */
        private List<RecipientDto> recipients;
        /** Total number of inline recipients (convenience count). */
        private long            recipientCount;
        private UUID            approvedBy;
        private String          approverName;
        private Instant         approvedAt;
        private UUID            createdBy;
        private String          creatorName;
        private Instant         createdAt;
    }

    @Data
    public static class CreateTemplateRequest {
        @NotBlank
        private String name;

        /** Optional description of what this template is used for. */
        private String description;

        @NotBlank
        private String body;

        /** GSM7 (default) or UCS2 */
        private String encoding;

        private List<String> variables;
        private String sender;

        /**
         * Optional ID of an existing contact group to use as recipients.
         * Can be combined with {@code recipients} — the sender merges both.
         */
        private UUID recipientGroupId;

        /**
         * Optional inline list of recipients.
         * Each entry must have a valid phone number (phoneE164 is required).
         */
        private List<@Valid RecipientRequest> recipients;
    }

    @Data
    public static class RecipientRequest {
        /** Phone number in E.164 format, e.g. +251911234567. Mandatory. */
        @NotBlank(message = "phoneE164 is required for every recipient")
        @Pattern(regexp = "\\+[1-9]\\d{6,14}", message = "phoneE164 must be in E.164 format (e.g. +251911234567)")
        private String phoneE164;

        /** Optional display name. */
        private String name;
    }

    @Data @AllArgsConstructor
    public static class RecipientDto {
        private UUID   id;
        private String phoneE164;
        private String name;
    }
}
