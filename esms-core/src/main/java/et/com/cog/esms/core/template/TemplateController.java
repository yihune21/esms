package et.com.cog.esms.core.template;

import et.com.cog.esms.core.audit.AuditService;
import et.com.cog.esms.core.identity.UserRepository;
import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateRepository            templateRepo;
    private final TemplateRecipientRepository   recipientRepo;
    private final UserRepository userRepo;
    private final AuditService   auditService;

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

    @PostMapping
    @PreAuthorize("hasAuthority('TEMPLATE_CREATE')")
    @Transactional
    public ResponseEntity<?> create(@Valid @RequestBody CreateTemplateRequest req) {
        if (req.getRecipientGroupId() == null && (req.getRecipients() == null || req.getRecipients().isEmpty())) {
            auditService.log(null, "TEMPLATE", "WARN",
                            "TEMPLATE_CREATE_BAD_REQUEST", "TEMPLATE", null);
           
            return ResponseEntity.badRequest().body(Map.of("title", "A template must have at least one recipient (either recipientGroupId or an inline recipient list)"));
        }

        UUID wsId = WorkspaceContext.currentWorkspaceId();

        if (templateRepo.existsByWorkspaceIdAndName(wsId, req.getName())) {
            auditService.log(null, "TEMPLATE", "WARN",
                            "TEMPLATE_NAME_DUPLICATED", "TEMPLATE", null);
           
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

        if (req.getRecipients() != null && !req.getRecipients().isEmpty()) {
            persistRecipients(saved.getId(), req.getRecipients());
        }
        auditService.log(wsId, "TEMPLATE", "INFO",
                            "TEMPLATE_CREATED", "TEMPLATE",saved.getId());
           
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_VIEW')")
    public ResponseEntity<TemplateDto> get(@PathVariable UUID id) {
        return templateRepo.findById(id)
                .map(t -> ResponseEntity.ok(toDto(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_CREATE')")
    @Transactional
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> updates) {
        Template t = templateRepo.findById(id).orElse(null);
        if (t == null) {
            return ResponseEntity.notFound().build();
        }

        // Everything is validated BEFORE the entity is touched.
        //
        // This method is @Transactional, so `t` is a managed entity: any field
        // set on it is flushed to the database when the transaction commits.
        // Returning ResponseEntity.badRequest() is a normal return, not an
        // exception, so it rolls nothing back — validating after mutating meant
        // a rejected edit was still saved, and the caller got a 400 telling
        // them it had not been.
        UUID targetGroupId = t.getRecipientGroupId();
        if (updates.containsKey("recipientGroupId")) {
            Object raw = updates.get("recipientGroupId");
            try {
                targetGroupId = raw != null ? UUID.fromString((String) raw) : null;
            } catch (IllegalArgumentException | ClassCastException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("title", "recipientGroupId must be a valid UUID"));
            }
        }
        if (targetGroupId == null && recipientRepo.countByTemplateId(id) == 0) {
            return ResponseEntity.badRequest().body(Map.of("title",
                    "A template must have at least one recipient (either recipientGroupId or an inline recipient list)"));
        }

        if (updates.containsKey("name"))        t.setName((String) updates.get("name"));
        if (updates.containsKey("description")) t.setDescription((String) updates.get("description"));
        if (updates.containsKey("body"))        t.setBody((String) updates.get("body"));
        if (updates.containsKey("encoding"))    t.setEncoding((String) updates.get("encoding"));
        if (updates.containsKey("sender"))      t.setSender((String) updates.get("sender"));
        if (updates.containsKey("recipientGroupId")) t.setRecipientGroupId(targetGroupId);
        if (updates.containsKey("variables")) {
            @SuppressWarnings("unchecked")
            List<String> vars = (List<String>) updates.get("variables");
            t.setVariables(vars);
        }
        // An edited template goes back to DRAFT so it must be re-approved.
        t.setStatus("DRAFT");

        Template saved = templateRepo.save(t);

        auditService.log(WorkspaceContext.currentWorkspaceId(), "TEMPLATE", "INFO",
                "TEMPLATE_UPDATED", "TEMPLATE", saved.getId());

        return ResponseEntity.ok(toDto(saved));
    }

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
                    boolean isSuperAdmin = SecurityContextHolder
                            .getContext().getAuthentication().getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
                    boolean isDeptHead = SecurityContextHolder
                            .getContext().getAuthentication().getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_DEPT_HEAD"));
                    if (t.getCreatedBy().equals(actorId) && !isSuperAdmin && !isDeptHead) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("title", "The creator cannot approve their own template"));
                    }
                    t.setStatus("APPROVED");
                    t.setApprovedBy(actorId);
                    t.setApprovedAt(Instant.now());
                    
                    Template saved  = templateRepo.save(t);

                    auditService.log(WorkspaceContext.currentWorkspaceId(), "TEMPLATE", "INFO",
                            "TEMPLATE_APPROVED", "TEMPLATE",saved.getId());
           
                    return ResponseEntity.ok(toDto(templateRepo.save(t)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

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

                    Template saved  = templateRepo.save(t);

                    auditService.log(WorkspaceContext.currentWorkspaceId(), "TEMPLATE", "INFO",
                            "TEMPLATE_REJECTED", "TEMPLATE",saved.getId());
           
                    return ResponseEntity.ok(toDto(templateRepo.save(t)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

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
                    Template saved  = templateRepo.save(t);
                    auditService.log(WorkspaceContext.currentWorkspaceId(), "TEMPLATE", "INFO",
                            "TEMPLATE_RETIRED", "TEMPLATE",saved.getId());
           
                    return ResponseEntity.ok(toDto(templateRepo.save(t)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

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

                    Template saved  = templateRepo.save(t);
                    auditService.log(WorkspaceContext.currentWorkspaceId(), "TEMPLATE", "INFO",
                            "TEMPLATE_REACTIVATED", "TEMPLATE",saved.getId());
           

                    return ResponseEntity.ok(toDto(templateRepo.save(t)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

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
                    auditService.log(WorkspaceContext.currentWorkspaceId(), "TEMPLATE", "INFO",
                            "TEMPLATE_RECIPIENTS_CREATED", "TEMPLATE_RECIPIENT",id);
           
                    return ResponseEntity.status(HttpStatus.CREATED).body(list);
                })
                .orElse(ResponseEntity.notFound().build());
    }

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
                    if (t.getRecipientGroupId() == null) {
                        long count = recipientRepo.countByTemplateId(id);
                        if (count <= 1) {
                            return ResponseEntity.badRequest().body(Map.of("title", "A template must have at least one recipient. Cannot remove the last recipient."));
                        }
                    }
                    recipientRepo.deleteByTemplateIdAndPhoneE164(id, phone);
                    auditService.log(WorkspaceContext.currentWorkspaceId(), "TEMPLATE", "INFO",
                            "TEMPLATE_RECIPIENT_DELETED", "TEMPLATE_RECIPIENT",t.getId());
           
                    return ResponseEntity.noContent().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

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

    @Data @AllArgsConstructor
    public static class TemplateDto {
        private UUID            id;
        private UUID            workspaceId;
        private String          name;
        private String          description;
        private String          body;
        private String          encoding;
        private String          status;
        private String          rejectionReason;
        private List<String>    variables;
        private String          sender;
        private UUID            recipientGroupId;
        private List<RecipientDto> recipients;
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

        private String description;

        @NotBlank
        private String body;

        private String encoding;

        private List<String> variables;
        private String sender;

        private UUID recipientGroupId;

        private List<@Valid RecipientRequest> recipients;
    }

    @Data
    public static class RecipientRequest {
        @NotBlank(message = "phoneE164 is required for every recipient")
        @Pattern(regexp = "\\+[1-9]\\d{6,14}", message = "phoneE164 must be in E.164 format (e.g. +251911234567)")
        private String phoneE164;

        private String name;
    }

    @Data @AllArgsConstructor
    public static class RecipientDto {
        private UUID   id;
        private String phoneE164;
        private String name;
    }
}
