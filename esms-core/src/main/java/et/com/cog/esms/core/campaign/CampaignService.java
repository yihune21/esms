package et.com.cog.esms.core.campaign;

import et.com.cog.esms.core.security.WorkspaceContext;
import et.com.cog.esms.core.workspace.Workspace;
import et.com.cog.esms.core.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        Map.entry("FINANCE:PENDING_CEO",     Set.of("APPROVED", "DRAFT"))
    );

    @Transactional
    public Campaign create(UUID workspaceId, String name, String kind,
                           UUID templateId, UUID recipientGroupId,
                           String customBody, Instant scheduledAt) {
        Campaign c = Campaign.builder()
                .workspaceId(workspaceId)
                .name(name)
                .kind(kind)
                .status("DRAFT")
                .templateId(templateId)
                .recipientGroupId(recipientGroupId)
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
