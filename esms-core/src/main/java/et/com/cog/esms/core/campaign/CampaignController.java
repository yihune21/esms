package et.com.cog.esms.core.campaign;

import et.com.cog.esms.core.messaging.MessageRepository;
import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    private final CampaignService  campaignService;
    private final CampaignRepository campaignRepo;
    private final et.com.cog.esms.core.identity.UserRepository userRepo;
    private final MessageRepository messageRepo;

    @GetMapping
    @PreAuthorize("hasAuthority('CAMPAIGN_VIEW')")
    public ResponseEntity<List<CampaignDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID workspaceId) {
        UUID wsId = resolveWorkspace(workspaceId);
        List<Campaign> campaigns;
        if (wsId == null) {
            campaigns = status != null ? campaignRepo.findByStatus(status) : campaignRepo.findAll();
        } else {
            campaigns = status != null
                    ? campaignRepo.findByWorkspaceIdAndStatus(wsId, status)
                    : campaignRepo.findByWorkspaceIdOrderByCreatedAtDesc(wsId);
        }
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
    @PreAuthorize("hasAnyAuthority('CAMPAIGN_APPROVE', 'CAMPAIGN_APPROVE_CEO')")
    public ResponseEntity<CampaignDto> approve(@PathVariable UUID id,
                                                @RequestBody(required = false) NoteRequest note) {
        // Fix 5: gate CEO-tier approval on the CAMPAIGN_APPROVE_CEO permission
        Campaign campaign = campaignRepo.findById(id).orElse(null);
        if (campaign == null) {
            return ResponseEntity.notFound().build();
        }
        boolean isCeoStage = "PENDING_CEO".equals(campaign.getStatus());
        boolean hasCeoPermission = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("CAMPAIGN_APPROVE_CEO"));
        boolean hasHeadPermission = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("CAMPAIGN_APPROVE"));

        if (isCeoStage && !hasCeoPermission) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(null);
        }
        if (!isCeoStage && !hasHeadPermission) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(null);
        }

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
        String creatorName = c.getCreatedBy() != null ? userRepo.findById(c.getCreatedBy())
                .map(et.com.cog.esms.core.identity.AppUser::getDisplayName).orElse(null) : null;

        // Per-campaign delivery stats
        long delivered  = messageRepo.countByCampaignIdAndStatusIn(c.getId(), List.of("DELIVERED"));
        long failed     = messageRepo.countByCampaignIdAndStatusIn(c.getId(), List.of("FAILED"));
        long sent       = messageRepo.countByCampaignIdAndStatusIn(c.getId(), List.of("SENT"));
        long total      = messageRepo.countByCampaignId(c.getId());
        double rate     = (sent + delivered) > 0
                ? Math.round(1000.0 * delivered / (sent + delivered)) / 10.0 : 0.0;

        return new CampaignDto(c.getId(), c.getName(), c.getKind(), c.getStatus(),
                c.getTemplateId(), c.getCustomBody(), c.getRecipientGroupId(), c.getUploadId(),
                c.getRecipientCount(), c.getScheduledAt(), c.getCreatedBy(), creatorName,
                c.getCreatedAt(), c.getWorkspaceId(),
                total, delivered, failed, rate);
    }

    private UUID resolveWorkspace(UUID overrideWsId) {
        boolean isSuperAdmin = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));

        if (isSuperAdmin && "00000000-0000-0000-0000-000000000000".equals(String.valueOf(overrideWsId))) {
            return null; // Platform-level aggregation
        }
        if (isSuperAdmin && overrideWsId != null) {
            return overrideWsId;
        }
        return WorkspaceContext.currentWorkspaceId();
    }

    @Data
    public static class UpdateCampaignRequest {
        private String  name;
        /** INSTANT | SCHEDULED */
        @Pattern(regexp = "INSTANT|SCHEDULED",
                 message = "kind must be one of: INSTANT, SCHEDULED")
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
        /** INSTANT | SCHEDULED */
        @NotBlank
        @Pattern(regexp = "INSTANT|SCHEDULED",
                 message = "kind must be one of: INSTANT, SCHEDULED")
        private String kind;
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
        private UUID    id;
        private String  name;
        /** INSTANT | SCHEDULED */
        private String  kind;
        private String  status;
        private UUID    templateId;
        private String  customBody;
        private UUID    recipientGroupId;
        private UUID    uploadId;
        private Integer recipientCount;
        private Instant scheduledAt;
        private UUID    createdBy;
        private String  creatorName;
        private Instant createdAt;
        private UUID    workspaceId;
        // ── inline delivery stats ──
        private long    totalMessages;
        private long    deliveredMessages;
        private long    failedMessages;
        private double  deliveryRatePct;
    }
}
