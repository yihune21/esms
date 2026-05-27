package et.com.cog.esms.core.template;

import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SMS Template REST controller.
 * Lifecycle: DRAFT → APPROVED → RETIRED.
 * Reference: LLD §4.4
 */
@Slf4j
@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateRepository templateRepo;

    // ── GET /templates ───────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAuthority('TEMPLATE_VIEW')")
    public ResponseEntity<List<TemplateDto>> list(
            @RequestParam(required = false) String status) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<Template> templates = status != null
                ? templateRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(wsId, status)
                : templateRepo.findByWorkspaceIdOrderByCreatedAtDesc(wsId);
        return ResponseEntity.ok(templates.stream().map(this::toDto).collect(Collectors.toList()));
    }

    // ── POST /templates ──────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasAuthority('TEMPLATE_CREATE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateTemplateRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();

        if (templateRepo.existsByWorkspaceIdAndName(wsId, req.getName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "A template with that name already exists in this workspace"));
        }

        Template t = Template.builder()
                .workspaceId(wsId)
                .name(req.getName())
                .body(req.getBody())
                .encoding(req.getEncoding() != null ? req.getEncoding() : "GSM7")
                .status("DRAFT")
                .createdBy(WorkspaceContext.currentUserId())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(templateRepo.save(t)));
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
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> updates) {
        return templateRepo.findById(id)
                .map(t -> {
                    if (!"DRAFT".equals(t.getStatus())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .<TemplateDto>body(null);
                    }
                    if (updates.containsKey("name"))     t.setName((String) updates.get("name"));
                    if (updates.containsKey("body"))     t.setBody((String) updates.get("body"));
                    if (updates.containsKey("encoding")) t.setEncoding((String) updates.get("encoding"));
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
                    if (t.getCreatedBy().equals(actorId)) {
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

    // ── Helpers ──────────────────────────────────────────────────

    private TemplateDto toDto(Template t) {
        return new TemplateDto(t.getId(), t.getWorkspaceId(), t.getName(), t.getBody(),
                t.getEncoding(), t.getStatus(), t.getApprovedBy(), t.getApprovedAt(),
                t.getCreatedBy(), t.getCreatedAt());
    }

    // ── DTOs ─────────────────────────────────────────────────────

    @Data @AllArgsConstructor
    public static class TemplateDto {
        private UUID id;
        private UUID workspaceId;
        private String name;
        private String body;
        private String encoding;
        private String status;
        private UUID approvedBy;
        private Instant approvedAt;
        private UUID createdBy;
        private Instant createdAt;
    }

    @Data
    public static class CreateTemplateRequest {
        @NotBlank private String name;
        @NotBlank private String body;
        /** GSM7 (default) or UCS2 */
        private String encoding;
    }
}
