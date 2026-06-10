package et.com.cog.esms.core.campaign;

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
 * Campaign REST controller — LLD
 */
@Slf4j
@RestController
@RequestMapping("/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;
    private final CampaignRepository campaignRepo;

    @GetMapping
    @PreAuthorize("hasAuthority('CAMPAIGN_VIEW')")
    public ResponseEntity<List<CampaignDto>> list(
            @RequestParam(required = false) String status) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<Campaign> campaigns = status != null
                ? campaignRepo.findByWorkspaceIdAndStatus(wsId, status)
                : campaignRepo.findByWorkspaceIdOrderByCreatedAtDesc(wsId);
        return ResponseEntity.ok(campaigns.stream().map(this::toDto).collect(Collectors.toList()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CAMPAIGN_DRAFT')")
    public ResponseEntity<CampaignDto> create(@Valid @RequestBody CreateCampaignRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        Campaign c = campaignService.create(wsId, req.getName(), req.getKind(),
                req.getTemplateId(), req.getRecipientGroupId(), req.getUploadId(),
                req.getCustomBody(), req.getScheduledAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(c));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('CAMPAIGN_SUBMIT')")
    public ResponseEntity<CampaignDto> submit(@PathVariable UUID id) {
        return ResponseEntity.ok(toDto(campaignService.submit(id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('CAMPAIGN_APPROVE')")
    public ResponseEntity<CampaignDto> approve(@PathVariable UUID id,
                                                @RequestBody(required = false) NoteRequest note) {
        String n = note != null ? note.getNote() : null;
        return ResponseEntity.ok(toDto(campaignService.approve(id, n)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('CAMPAIGN_APPROVE')")
    public ResponseEntity<CampaignDto> reject(@PathVariable UUID id,
                                               @Valid @RequestBody NoteRequest note) {
        return ResponseEntity.ok(toDto(campaignService.reject(id, note.getNote())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGN_DRAFT')")
    public ResponseEntity<CampaignDto> update(@PathVariable UUID id,
                                               @RequestBody UpdateCampaignRequest req) {
        Campaign c = campaignService.update(id,
                req.getName(), req.getKind(),
                req.getTemplateId(), req.getRecipientGroupId(), req.getUploadId(),
                req.getCustomBody(), req.getScheduledAt());
        return ResponseEntity.ok(toDto(c));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('CAMPAIGN_CANCEL')")
    public ResponseEntity<CampaignDto> cancel(@PathVariable UUID id,
                                               @RequestBody(required = false) NoteRequest note) {
        String n = note != null ? note.getNote() : null;
        return ResponseEntity.ok(toDto(campaignService.cancel(id, n)));
    }

    private CampaignDto toDto(Campaign c) {
        return new CampaignDto(c.getId(), c.getName(), c.getKind(), c.getStatus(),
                c.getTemplateId(), c.getCustomBody(), c.getRecipientGroupId(), c.getUploadId(),
                c.getRecipientCount(), c.getScheduledAt(), c.getCreatedAt());
    }

    @Data
    public static class UpdateCampaignRequest {
        private String  name;
        private String  kind;
        private UUID    templateId;
        private UUID    recipientGroupId;
        private UUID    uploadId;
        private String  customBody;
        private Instant scheduledAt;
    }

    @Data
    public static class CreateCampaignRequest {
        @NotBlank private String name;
        @NotBlank private String kind;
        private UUID templateId;
        private UUID recipientGroupId;
        private UUID uploadId;
        private String customBody;
        private Instant scheduledAt;
    }

    @Data
    public static class NoteRequest {
        private String note;
    }

    @Data @AllArgsConstructor
    public static class CampaignDto {
        private UUID id;
        private String name;
        private String kind;
        private String status;
        private UUID templateId;
        private String customBody;
        private UUID recipientGroupId;
        private UUID uploadId;
        private Integer recipientCount;
        private Instant scheduledAt;
        private Instant createdAt;
    }
}
