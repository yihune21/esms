package et.com.cog.esms.core.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.core.messaging.OutboxEvent;
import et.com.cog.esms.core.messaging.OutboxEventRepository;
import et.com.cog.esms.core.security.WorkspaceContext;
import et.com.cog.esms.core.workspace.Workspace;
import et.com.cog.esms.core.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Campaign service — handles lifecycle and approval state machine.
 * Enforces: allowed transitions, approver ≠ drafter, workspace kind rules.
 * Reference: LLD §7
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignRepository campaignRepo;
    private final ApprovalRepository approvalRepo;
    private final WorkspaceRepository workspaceRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    /** Allowed transitions per workspace kind — mirrors the DB trigger. */
    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
        // Standard 1-tier
        Map.entry("UNDERWRITING:DRAFT",            Set.of("PENDING_APPROVAL")),
        Map.entry("CLAIMS:DRAFT",                  Set.of("PENDING_APPROVAL")),
        Map.entry("MARKETING:DRAFT",               Set.of("PENDING_APPROVAL")),
        Map.entry("GENERIC:DRAFT",                 Set.of("PENDING_APPROVAL")),
        Map.entry("UNDERWRITING:PENDING_APPROVAL",  Set.of("APPROVED", "DRAFT")),
        Map.entry("CLAIMS:PENDING_APPROVAL",        Set.of("APPROVED", "DRAFT")),
        Map.entry("MARKETING:PENDING_APPROVAL",     Set.of("APPROVED", "DRAFT")),
        Map.entry("GENERIC:PENDING_APPROVAL",       Set.of("APPROVED", "DRAFT")),
        // Finance 2-tier
        Map.entry("FINANCE:DRAFT",           Set.of("PENDING_HEAD")),
        Map.entry("FINANCE:PENDING_HEAD",    Set.of("PENDING_CEO", "DRAFT")),
        Map.entry("FINANCE:PENDING_CEO",     Set.of("APPROVED", "DRAFT")),
        // Cancellation — any workspace kind, from APPROVED or QUEUED
        Map.entry("UNDERWRITING:APPROVED",   Set.of("CANCELLED")),
        Map.entry("UNDERWRITING:QUEUED",     Set.of("CANCELLED")),
        Map.entry("CLAIMS:APPROVED",         Set.of("CANCELLED")),
        Map.entry("CLAIMS:QUEUED",           Set.of("CANCELLED")),
        Map.entry("MARKETING:APPROVED",      Set.of("CANCELLED")),
        Map.entry("MARKETING:QUEUED",        Set.of("CANCELLED")),
        Map.entry("GENERIC:APPROVED",        Set.of("CANCELLED")),
        Map.entry("GENERIC:QUEUED",          Set.of("CANCELLED")),
        Map.entry("FINANCE:APPROVED",        Set.of("CANCELLED")),
        Map.entry("FINANCE:QUEUED",          Set.of("CANCELLED"))
    );

    @Transactional
    public Campaign create(UUID workspaceId, String name, String kind,
                           UUID templateId, UUID recipientGroupId, UUID uploadId,
                           String customBody, Instant scheduledAt) {
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
                .build();
        return campaignRepo.save(c);
    }

    @Transactional
    public Campaign submit(UUID campaignId) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        Workspace ws = workspaceRepo.findById(c.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        String targetState = "FINANCE".equals(ws.getKind()) ? "PENDING_HEAD" : "PENDING_APPROVAL";
        validateTransition(ws.getKind(), c.getStatus(), targetState);

        recordApproval(c, c.getStatus(), targetState, null);
        c.setStatus(targetState);
        return campaignRepo.save(c);
    }

    @Transactional
    public Campaign approve(UUID campaignId, String note) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        Workspace ws = workspaceRepo.findById(c.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        UUID actorId = WorkspaceContext.currentUserId();

        // Approver ≠ Drafter
        if (c.getCreatedBy().equals(actorId)) {
            throw new IllegalStateException("The drafter cannot approve their own campaign");
        }

        // Determine target state
        String targetState;
        if ("PENDING_HEAD".equals(c.getStatus())) {
            targetState = "PENDING_CEO";
        } else if ("PENDING_CEO".equals(c.getStatus())) {
            targetState = "APPROVED";
        } else if ("PENDING_APPROVAL".equals(c.getStatus())) {
            targetState = "APPROVED";
        } else {
            throw new IllegalStateException("Campaign is not in an approvable state: " + c.getStatus());
        }

        validateTransition(ws.getKind(), c.getStatus(), targetState);
        recordApproval(c, c.getStatus(), targetState, note);
        c.setStatus(targetState);
        return campaignRepo.save(c);
    }

    @Transactional
    public Campaign reject(UUID campaignId, String note) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        Workspace ws = workspaceRepo.findById(c.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        validateTransition(ws.getKind(), c.getStatus(), "DRAFT");
        recordApproval(c, c.getStatus(), "DRAFT", note);
        c.setStatus("DRAFT");
        return campaignRepo.save(c);
    }

    /**
     * Update a campaign that is still in DRAFT status.
     * Any subset of fields may be changed; null values leave the field unchanged.
     */
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

    /**
     * Cancel an APPROVED or QUEUED campaign.
     * Records an audit trail entry and transitions status to CANCELLED.
     */
    @Transactional
    public Campaign cancel(UUID campaignId, String note) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        Workspace ws = workspaceRepo.findById(c.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        validateTransition(ws.getKind(), c.getStatus(), "CANCELLED");
        recordApproval(c, c.getStatus(), "CANCELLED", note);
        c.setStatus("CANCELLED");
        c.setCompletedAt(Instant.now());
        log.info("Campaign cancelled: id={}, by={}", campaignId, WorkspaceContext.currentUserId());
        return campaignRepo.save(c);
    }

    // ── Scheduled campaign poller ─────────────────────────────────

    /**
     * Runs every minute. Finds all APPROVED campaigns of kind=SCHEDULED whose
     * {@code scheduled_at} timestamp has passed, transitions them to QUEUED,
     * and publishes an OutboxEvent so esms-sender dispatches the messages.
     *
     * This is the "send an SMS at a specific future date" feature.
     * It is completely separate from Reminders (date-triggered, expiry-based sends).
     */
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

    // ── Internal ─────────────────────────────────────────────────

    private void validateTransition(String wsKind, String fromState, String toState) {
        String key = wsKind + ":" + fromState;
        Set<String> allowed = TRANSITIONS.get(key);
        if (allowed == null || !allowed.contains(toState)) {
            throw new IllegalStateException(
                    String.format("Illegal transition %s → %s for workspace kind %s",
                            fromState, toState, wsKind));
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
