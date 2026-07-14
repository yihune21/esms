package et.com.cog.esms.core.campaign;

import et.com.cog.esms.core.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.core.messaging.OutboxEvent;
import et.com.cog.esms.core.messaging.OutboxEventRepository;
import et.com.cog.esms.core.security.WorkspaceContext;
import et.com.cog.esms.core.workspace.Workspace;
import et.com.cog.esms.core.workspace.WorkspacePermissionRepository;
import et.com.cog.esms.core.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;


@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignRepository campaignRepo;
    private final ApprovalRepository approvalRepo;
    private final WorkspaceRepository workspaceRepo;
    private final WorkspacePermissionRepository workspacePermissionRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;


    // Keyed by the FROM state only, deliberately tier-agnostic. The tier
    // (one- vs two-step approval) decides which TARGET a submit/approve aims
    // for — but validation must accept a state that belonged to the other
    // tier, because a workspace's delegation setting can be toggled while a
    // campaign is mid-flight. A campaign submitted as PENDING_HEAD (two-tier)
    // must still be approvable straight to APPROVED after delegation is turned
    // off, and a PENDING_APPROVAL (one-tier) campaign must stay valid if
    // delegation is later turned on. So PENDING_HEAD allows BOTH PENDING_CEO
    // (still two-tier) and APPROVED (now one-tier); the caller picks which.
    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
        Map.entry("DRAFT",            Set.of("PENDING_APPROVAL", "PENDING_HEAD")),
        Map.entry("PENDING_APPROVAL", Set.of("APPROVED", "DRAFT")),
        Map.entry("PENDING_HEAD",     Set.of("PENDING_CEO", "APPROVED", "DRAFT")),
        Map.entry("PENDING_CEO",      Set.of("APPROVED", "DRAFT")),
        Map.entry("APPROVED",         Set.of("CANCELLED", "QUEUED")),
        Map.entry("QUEUED",           Set.of("CANCELLED", "COMPLETED"))
    );

    // Tiering is decided by whether the workspace has the DELEGATION feature
    // enabled (workspace_permission), not by workspace kind — kind is just a
    // department tag and every workspace created through the app defaults to
    // "GENERIC", so kind-based tiering meant the two-tier chain was only ever
    // reachable for the one seeded kind="FINANCE" workspace, regardless of
    // whether an admin had actually turned delegation on.
    private boolean isTwoTier(Workspace ws) {
        return workspacePermissionRepo.existsByWorkspaceIdAndPermissionCode(ws.getId(), "DELEGATION");
    }

    @Transactional
    public Campaign create(UUID workspaceId, String name, String kind,
                           UUID templateId, UUID recipientGroupId, UUID uploadId,
                           String customBody, Instant scheduledAt) {
        if ("SCHEDULED".equals(kind)) {
            if (scheduledAt == null) {
                throw new IllegalArgumentException("scheduledAt is required for SCHEDULED campaigns");
            }
            if (!scheduledAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("scheduledAt must be a future date/time");
            }
        }
        if (recipientGroupId == null && uploadId == null) {
            throw new IllegalArgumentException(
                    "A campaign must have a recipient source: provide recipientGroupId (contact group) or uploadId (file upload)");
        }
        if (templateId == null && (customBody == null || customBody.isBlank())) {
            throw new IllegalArgumentException(
                    "A campaign must have a message: provide templateId or customBody");
        }
        Campaign c = Campaign.builder()
                .workspaceId(workspaceId)
                .name(name)
                .kind(kind)
                .status("DRAFT")
                .templateId(templateId)
                .recipientGroupId(recipientGroupId)
                .uploadId(uploadId)
                .customBody(customBody)
                .scheduledAt(scheduledAt)
                .createdBy(WorkspaceContext.currentUserId())
                .createdAt(Instant.now())
                .build();
        Campaign saved = campaignRepo.save(c);
        auditService.log(workspaceId, "CAMPAIGN", "INFO", "CAMPAIGN_CREATED", "Campaign", saved.getId());
        return saved;
    }

    @Transactional
    public Campaign submit(UUID campaignId, UUID actorId) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        // Fix #7: only the campaign creator may submit it
        if (!c.getCreatedBy().equals(actorId)) {
            throw new IllegalStateException("Only the campaign creator can submit this campaign");
        }

        Workspace ws = workspaceRepo.findById(c.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        boolean twoTier = isTwoTier(ws);

        String targetState = twoTier ? "PENDING_HEAD" : "PENDING_APPROVAL";
        validateTransition(c.getStatus(), targetState);

        recordApproval(c, c.getStatus(), targetState, null);
        c.setStatus(targetState);
        Campaign saved = campaignRepo.save(c);
        auditService.log(saved.getWorkspaceId(), "CAMPAIGN", "INFO",
                "CAMPAIGN_SUBMITTED", "Campaign", saved.getId());
        return saved;
    }

    @Transactional
    public Campaign approve(UUID campaignId, String note) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        Workspace ws = workspaceRepo.findById(c.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        boolean twoTier = isTwoTier(ws);
        UUID actorId = WorkspaceContext.currentUserId();

        
        boolean isSuperAdmin = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        boolean isDeptHead = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DEPT_HEAD"));

        if (c.getCreatedBy().equals(actorId) && !isSuperAdmin && !isDeptHead) {
            throw new IllegalStateException("The drafter cannot approve their own campaign");
        }

        String targetState;
        if ("PENDING_HEAD".equals(c.getStatus())) {
            targetState = twoTier ? "PENDING_CEO" : "APPROVED";
        } else if ("PENDING_CEO".equals(c.getStatus())) {
            targetState = "APPROVED";
        } else if ("PENDING_APPROVAL".equals(c.getStatus())) {
            targetState = "APPROVED";
        } else {
            throw new IllegalStateException("Campaign is not in an approvable state: " + c.getStatus());
        }

        validateTransition(c.getStatus(), targetState);
        recordApproval(c, c.getStatus(), targetState, note);
        c.setStatus(targetState);
        Campaign saved = campaignRepo.save(c);

        if ("APPROVED".equals(targetState)) {
            saved = queueIfInstant(saved);
        }
        auditService.log(saved.getWorkspaceId(), "CAMPAIGN", "INFO",
                "CAMPAIGN_APPROVED_TO_" + saved.getStatus(), "Campaign", saved.getId());
        return saved;
    }

    // Once a campaign reaches APPROVED, an INSTANT one is queued to send right
    // away (SCHEDULED ones wait for the poller). Shared by approve() and the
    // delegation-disabled finalizer so both queue identically.
    private Campaign queueIfInstant(Campaign saved) {
        if (!"INSTANT".equals(saved.getKind())) return saved;
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("campaignId",  saved.getId().toString());
            payload.put("workspaceId", saved.getWorkspaceId().toString());
            if (saved.getTemplateId()        != null) payload.put("templateId",      saved.getTemplateId().toString());
            if (saved.getCustomBody()        != null) payload.put("customBody",       saved.getCustomBody());
            if (saved.getRecipientGroupId()  != null) payload.put("recipientGroupId", saved.getRecipientGroupId().toString());
            if (saved.getUploadId()          != null) payload.put("uploadId",         saved.getUploadId().toString());
            payload.put("kind", saved.getKind());

            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType("campaign")
                    .aggregateId(saved.getId())
                    .eventType("ScheduledFire")
                    .payload(json)
                    .createdAt(Instant.now())
                    .build();
            outboxRepo.save(event);

            saved.setStatus("QUEUED");
            saved = campaignRepo.save(saved);
            log.info("Instant campaign queued immediately: id={}", saved.getId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for instant campaign id={}: {}", saved.getId(), e.getMessage(), e);
        }
        return saved;
    }

    /**
     * Graceful teardown of the delegate approval tier. When a workspace turns
     * delegation OFF, any campaign sitting in PENDING_CEO has already cleared
     * head review and was only waiting on a delegate who no longer exists —
     * leaving it stuck forever. Finalize those to APPROVED (queuing instant
     * ones), so disabling delegation can never orphan an in-flight campaign.
     * Returns how many were finalized.
     */
    @Transactional
    public int finalizePendingDelegateApprovals(UUID workspaceId) {
        List<Campaign> stuck = campaignRepo.findByWorkspaceIdAndStatus(workspaceId, "PENDING_CEO");
        for (Campaign c : stuck) {
            validateTransition(c.getStatus(), "APPROVED");
            recordApproval(c, c.getStatus(), "APPROVED",
                    "Delegate sign-off removed — auto-finalized after head approval");
            c.setStatus("APPROVED");
            Campaign saved = queueIfInstant(campaignRepo.save(c));
            auditService.log(saved.getWorkspaceId(), "CAMPAIGN", "INFO",
                    "CAMPAIGN_AUTO_FINALIZED_DELEGATION_OFF", "Campaign", saved.getId());
        }
        if (!stuck.isEmpty()) {
            log.info("Delegation disabled for workspace {}: finalized {} PENDING_CEO campaign(s)",
                    workspaceId, stuck.size());
        }
        return stuck.size();
    }

    @Transactional
    public Campaign reject(UUID campaignId, String note) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        validateTransition(c.getStatus(), "DRAFT");
        recordApproval(c, c.getStatus(), "DRAFT", note);
        c.setStatus("DRAFT");
        Campaign saved = campaignRepo.save(c);
        auditService.log(saved.getWorkspaceId(), "CAMPAIGN", "WARN",
                "CAMPAIGN_REJECTED", "Campaign", saved.getId());
        return saved;
    }

    
    @Transactional
    public Campaign update(UUID campaignId, String name, String kind,
                           UUID templateId, UUID recipientGroupId, UUID uploadId,
                           String customBody, Instant scheduledAt) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        if (!"DRAFT".equals(c.getStatus())) {
            throw new IllegalStateException(
                    "Only DRAFT campaigns can be edited. Current status: " + c.getStatus());
        }

        if (name       != null) c.setName(name);
        if (kind       != null) c.setKind(kind);
        if (templateId != null) c.setTemplateId(templateId);
        if (recipientGroupId != null) c.setRecipientGroupId(recipientGroupId);
        if (uploadId   != null) c.setUploadId(uploadId);
        if (customBody != null) c.setCustomBody(customBody);
        if (scheduledAt != null) c.setScheduledAt(scheduledAt);

        log.info("Campaign updated: id={}, by={}", campaignId, WorkspaceContext.currentUserId());
        return campaignRepo.save(c);
    }

   
    @Transactional
    public Campaign cancel(UUID campaignId, String note) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        validateTransition(c.getStatus(), "CANCELLED");
        recordApproval(c, c.getStatus(), "CANCELLED", note);
        c.setStatus("CANCELLED");
        c.setCompletedAt(Instant.now());
        log.info("Campaign cancelled: id={}, by={}", campaignId, WorkspaceContext.currentUserId());
        Campaign saved = campaignRepo.save(c);
        auditService.log(saved.getWorkspaceId(), "CAMPAIGN", "WARN",
                "CAMPAIGN_CANCELLED", "Campaign", saved.getId());
        return saved;
    }

   
    @Scheduled(fixedDelayString = "${esms.scheduler.campaign-poll-ms:60000}")
    @Transactional
    public void processDueScheduledCampaigns() {
        List<Campaign> due = campaignRepo.findDueScheduledCampaigns(Instant.now());
        if (due.isEmpty()) return;

        log.info("Scheduled campaign poller: {} campaign(s) are due", due.size());
        for (Campaign c : due) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("campaignId",  c.getId().toString());
                payload.put("workspaceId", c.getWorkspaceId().toString());
                if (c.getTemplateId()        != null) payload.put("templateId",      c.getTemplateId().toString());
                if (c.getCustomBody()        != null) payload.put("customBody",       c.getCustomBody());
                if (c.getRecipientGroupId()  != null) payload.put("recipientGroupId", c.getRecipientGroupId().toString());
                if (c.getUploadId()          != null) payload.put("uploadId",         c.getUploadId().toString());
                payload.put("kind", c.getKind());

                String json = objectMapper.writeValueAsString(payload);
                OutboxEvent event = OutboxEvent.builder()
                        .aggregateType("campaign")
                        .aggregateId(c.getId())
                        .eventType("ScheduledFire")
                        .payload(json)
                        .createdAt(Instant.now())
                        .build();
                outboxRepo.save(event);

                c.setStatus("QUEUED");
                campaignRepo.save(c);
                log.info("Scheduled campaign queued: id={}", c.getId());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize payload for campaign id={}: {}", c.getId(), e.getMessage(), e);
            } catch (Exception ex) {
                log.error("Failed to process scheduled campaign id={}: {}", c.getId(), ex.getMessage(), ex);
            }
        }
    }


    private void validateTransition(String fromState, String toState) {
        Set<String> allowed = TRANSITIONS.get(fromState);
        if (allowed == null || !allowed.contains(toState)) {
            throw new IllegalStateException(
                    String.format("Illegal transition %s → %s", fromState, toState));
        }
    }

    private void recordApproval(Campaign c, String fromState, String toState, String note) {
        Approval approval = Approval.builder()
                .workspaceId(c.getWorkspaceId())
                .campaignId(c.getId())
                .fromState(fromState)
                .toState(toState)
                .actorUserId(WorkspaceContext.currentUserId())
                .note(note)
                .createdAt(Instant.now())
                .build();
        approvalRepo.save(approval);
    }
}
