package et.com.cog.esms.core.campaign;

import et.com.cog.esms.core.audit.AuditService;
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


@Slf4j
@RestController
@RequestMapping("/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService  campaignService;
    private final CampaignRepository campaignRepo;
    private final et.com.cog.esms.core.identity.UserRepository userRepo;
    private final MessageRepository messageRepo;
    private final AuditService auditService;

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
    public ResponseEntity<?> create(@Valid @RequestBody CreateCampaignRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        try {
            Campaign c = campaignService.create(wsId, req.getName(), req.getKind(),
                    req.getTemplateId(), req.getRecipientGroupId(), req.getUploadId(),
                    req.getCustomBody(), req.getScheduledAt());

            auditService.log(wsId, "CAMPAIGN", "INFO", "CAMPAIGN_CREATED", "Campaign", c.getId());

            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(c));
        } catch (IllegalArgumentException e) {
            auditService.log(wsId, "CAMPAIGN", "WARN", "CAMPAIGN_CREATE_REJECTED", "Campaign", null);
            return ResponseEntity.badRequest().body(Map.of("title", e.getMessage()));
        }
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('CAMPAIGN_SUBMIT')")
    public ResponseEntity<?> submit(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        try {
            UUID actorId = WorkspaceContext.currentUserId();
            Campaign c = campaignService.submit(id, actorId);

            auditService.log(wsId, "CAMPAIGN", "INFO", "CAMPAIGN_SUBMITTED", "Campaign", id);

            return ResponseEntity.ok(toDto(c));
        } catch (IllegalStateException e) {
            auditService.log(wsId, "CAMPAIGN", "WARN", "CAMPAIGN_SUBMIT_REJECTED_STATE", "Campaign", id);
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("title", e.getMessage()));
        } catch (IllegalArgumentException e) {
            auditService.log(wsId, "CAMPAIGN", "WARN", "CAMPAIGN_SUBMIT_REJECTED_INVALID", "Campaign", id);
            return ResponseEntity.badRequest().body(Map.of("title", e.getMessage()));
        }
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('CAMPAIGN_APPROVE', 'CAMPAIGN_APPROVE_CEO')")
    public ResponseEntity<CampaignDto> approve(@PathVariable UUID id,
                                                @RequestBody(required = false) NoteRequest note) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();

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
            auditService.log(wsId, "CAMPAIGN", "CRITICAL",
                    "CAMPAIGN_APPROVE_DENIED_CEO_TIER", "Campaign", id);
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(null);
        }
        if (!isCeoStage && !hasHeadPermission) {
            auditService.log(wsId, "CAMPAIGN", "WARN",
                    "CAMPAIGN_APPROVE_DENIED", "Campaign", id);
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(null);
        }

        String n = note != null ? note.getNote() : null;
        try {
            Campaign approved = campaignService.approve(id, n);

            auditService.log(wsId, "CAMPAIGN", "INFO",
                    isCeoStage ? "CAMPAIGN_APPROVED_CEO" : "CAMPAIGN_APPROVED", "Campaign", id);

            return ResponseEntity.ok(toDto(approved));
        } catch (IllegalStateException e) {
            auditService.log(wsId, "CAMPAIGN", "WARN", "CAMPAIGN_APPROVE_REJECTED_STATE", "Campaign", id);
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(null);
        }
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('CAMPAIGN_APPROVE')")
    public ResponseEntity<CampaignDto> reject(@PathVariable UUID id,
                                               @Valid @RequestBody NoteRequest note) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        Campaign rejected = campaignService.reject(id, note.getNote());

        auditService.log(wsId, "CAMPAIGN", "WARN", "CAMPAIGN_REJECTED", "Campaign", id);

        return ResponseEntity.ok(toDto(rejected));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGN_DRAFT')")
    public ResponseEntity<CampaignDto> update(@PathVariable UUID id,
                                               @RequestBody UpdateCampaignRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        Campaign c = campaignService.update(id,
                req.getName(), req.getKind(),
                req.getTemplateId(), req.getRecipientGroupId(), req.getUploadId(),
                req.getCustomBody(), req.getScheduledAt());

        auditService.log(wsId, "CAMPAIGN", "INFO", "CAMPAIGN_UPDATED", "Campaign", id);

        return ResponseEntity.ok(toDto(c));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('CAMPAIGN_CANCEL')")
    public ResponseEntity<?> cancel(@PathVariable UUID id,
                                               @RequestBody(required = false) NoteRequest note) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        try {
            String n = note != null ? note.getNote() : null;
            Campaign cancelled = campaignService.cancel(id, n);

            auditService.log(wsId, "CAMPAIGN", "WARN", "CAMPAIGN_CANCELLED", "Campaign", id);

            return ResponseEntity.ok(toDto(cancelled));
        } catch (IllegalStateException e) {
            auditService.log(wsId, "CAMPAIGN", "WARN", "CAMPAIGN_CANCEL_REJECTED_STATE", "Campaign", id);
            return ResponseEntity.badRequest().body(Map.of("title", e.getMessage()));
        } catch (IllegalArgumentException e) {
            auditService.log(wsId, "CAMPAIGN", "WARN", "CAMPAIGN_CANCEL_REJECTED_INVALID", "Campaign", id);
            return ResponseEntity.badRequest().body(Map.of("title", e.getMessage()));
        }
    }

    private CampaignDto toDto(Campaign c) {
        String creatorName = c.getCreatedBy() != null ? userRepo.findById(c.getCreatedBy())
                .map(et.com.cog.esms.core.identity.AppUser::getDisplayName).orElse(null) : null;

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

        if (isSuperAdmin && overrideWsId != null) {
            return "00000000-0000-0000-0000-000000000000".equals(overrideWsId.toString()) ? null : overrideWsId;
        }
        if (isSuperAdmin) {
            return null;
        }
        return WorkspaceContext.currentWorkspaceId();
    }

    @Data
    public static class UpdateCampaignRequest {
        private String  name;
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
        private long    totalMessages;
        private long    deliveredMessages;
        private long    failedMessages;
        private double  deliveryRatePct;
    }
}
