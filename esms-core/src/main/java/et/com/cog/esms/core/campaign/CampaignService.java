package et.com.cog.esms.core.campaign;

import et.com.cog.esms.core.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.core.messaging.OutboxEvent;
import et.com.cog.esms.core.messaging.OutboxEventRepository;
import et.com.cog.esms.core.security.WorkspaceContext;
import et.com.cog.esms.core.workspace.Workspace;
import et.com.cog.esms.core.workspace.WorkspaceRepository;
import et.com.cog.esms.core.workspace.WorkspacePermissionRepository;
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
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;
    private final WorkspacePermissionRepository permissionRepo;
    private final AuditService auditService;


    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
        Map.entry("UNDERWRITING:DRAFT",            Set.of("PENDING_APPROVAL")),
        Map.entry("CLAIMS:DRAFT",                  Set.of("PENDING_APPROVAL")),
        Map.entry("MARKETING:DRAFT",               Set.of("PENDING_APPROVAL")),
        Map.entry("GENERIC:DRAFT",                 Set.of("PENDING_APPROVAL")),
        Map.entry("UNDERWRITING:PENDING_APPROVAL",  Set.of("APPROVED", "DRAFT")),
        Map.entry("CLAIMS:PENDING_APPROVAL",        Set.of("APPROVED", "DRAFT")),
        Map.entry("MARKETING:PENDING_APPROVAL",     Set.of("APPROVED", "DRAFT")),
        Map.entry("GENERIC:PENDING_APPROVAL",       Set.of("APPROVED", "DRAFT")),

        Map.entry("DELEGATION:DRAFT",              Set.of("PENDING_HEAD")),
        Map.entry("DELEGATION:PENDING_HEAD",       Set.of("PENDING_CEO", "DRAFT")),
        Map.entry("DELEGATION:PENDING_CEO",        Set.of("APPROVED", "DRAFT")),
        Map.entry("DELEGATION:APPROVED",           Set.of("CANCELLED")),
        Map.entry("DELEGATION:QUEUED",             Set.of("CANCELLED")),

        Map.entry("FINANCE:DRAFT",           Set.of("PENDING_HEAD")),
        Map.entry("FINANCE:PENDING_HEAD",    Set.of("PENDING_CEO", "DRAFT")),
        Map.entry("FINANCE:PENDING_CEO",     Set.of("APPROVED", "DRAFT")),

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

        boolean hasDelegation = permissionRepo.existsByWorkspaceIdAndPermissionCode(ws.getId(), "DELEGATION");

        String targetState = ("FINANCE".equals(ws.getKind()) || hasDelegation) ? "PENDING_HEAD" : "PENDING_APPROVAL";
        validateTransition(ws.getKind(), c.getStatus(), targetState, hasDelegation);

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

        boolean hasDelegation = permissionRepo.existsByWorkspaceIdAndPermissionCode(ws.getId(), "DELEGATION");
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
            targetState = ("FINANCE".equals(ws.getKind()) || hasDelegation) ? "PENDING_CEO" : "APPROVED";
        } else if ("PENDING_CEO".equals(c.getStatus())) {
            targetState = "APPROVED";
        } else if ("PENDING_APPROVAL".equals(c.getStatus())) {
            targetState = "APPROVED";
        } else {
            throw new IllegalStateException("Campaign is not in an approvable state: " + c.getStatus());
        }

        validateTransition(ws.getKind(), c.getStatus(), targetState, hasDelegation);
        recordApproval(c, c.getStatus(), targetState, note);
        c.setStatus(targetState);
        Campaign saved = campaignRepo.save(c);

        if ("APPROVED".equals(targetState) && "INSTANT".equals(saved.getKind())) {
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
                        .build();
                outboxRepo.save(event);

                saved.setStatus("QUEUED");
                saved = campaignRepo.save(saved);
                log.info("Instant campaign queued immediately: id={}", saved.getId());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize payload for instant campaign id={}: {}", saved.getId(), e.getMessage(), e);
            }
        }
        auditService.log(saved.getWorkspaceId(), "CAMPAIGN", "INFO",
                "CAMPAIGN_APPROVED_TO_" + saved.getStatus(), "Campaign", saved.getId());
        return saved;
    }

    @Transactional
    public Campaign reject(UUID campaignId, String note) {
        Campaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));

        Workspace ws = workspaceRepo.findById(c.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        boolean hasDelegation = permissionRepo.existsByWorkspaceIdAndPermissionCode(ws.getId(), "DELEGATION");

        validateTransition(ws.getKind(), c.getStatus(), "DRAFT", hasDelegation);
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

        Workspace ws = workspaceRepo.findById(c.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        boolean hasDelegation = permissionRepo.existsByWorkspaceIdAndPermissionCode(ws.getId(), "DELEGATION");

        validateTransition(ws.getKind(), c.getStatus(), "CANCELLED", hasDelegation);
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


    private void validateTransition(String wsKind, String fromState, String toState, boolean hasDelegation) {
       
        String effectiveKind;
        if (hasDelegation && !"FINANCE".equals(wsKind)) {
            effectiveKind = "DELEGATION";
        } else if (hasDelegation || "FINANCE".equals(wsKind)) {
            effectiveKind = "FINANCE";
        } else {
            effectiveKind = wsKind;
        }
        String key = effectiveKind + ":" + fromState;
        Set<String> allowed = TRANSITIONS.get(key);
        if (allowed == null || !allowed.contains(toState)) {
            throw new IllegalStateException(
                    String.format("Illegal transition %s → %s for workspace kind %s (delegation=%b)",
                            fromState, toState, wsKind, hasDelegation));
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
