package et.com.cog.esms.core.reminder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.core.campaign.Approval;
import et.com.cog.esms.core.campaign.ApprovalRepository;
import et.com.cog.esms.core.messaging.OutboxEvent;
import et.com.cog.esms.core.messaging.OutboxEventRepository;
import et.com.cog.esms.core.security.WorkspaceContext;
import et.com.cog.esms.core.template.Template;
import et.com.cog.esms.core.template.TemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages reminder RUNS (the `schedule` table). A run is one send of a
 * reminder template against uploaded policy data — a separate, approval-gated
 * instance created every time the user presses Send. Approval is required for
 * every run (even if a previous run of the same template was approved), and
 * approving a run immediately fires it. The reusable template definition lives
 * in {@link ReminderTemplateService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepository reminderRepo;
    private final ReminderTemplateRepository reminderTemplateRepo;
    private final TemplateRepository messageTemplateRepo;
    private final ApprovalRepository approvalRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    // Sending: create a run from an ACTIVE template + an uploaded dataset. The
    // template's message/rule fields are snapshotted onto the run so a later
    // template edit can't change what was approved. The run is born
    // PENDING_APPROVAL — approval is required for this specific send.
    @Transactional
    public Reminder createRun(UUID workspaceId, UUID reminderTemplateId, UUID uploadId) {
        if (uploadId == null) {
            throw new IllegalArgumentException("Uploaded policy data is required to send a reminder");
        }
        ReminderTemplate t = reminderTemplateRepo.findById(reminderTemplateId)
                .orElseThrow(() -> new IllegalArgumentException("Reminder template not found: " + reminderTemplateId));
        if (!t.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalStateException("Reminder template does not belong to this workspace");
        }
        if (!"ACTIVE".equals(t.getStatus())) {
            throw new IllegalStateException("This reminder is deactivated and can't be sent");
        }
        // A run that renders from a message template can only go out if that
        // message template is still approved (not retired/rejected).
        if (t.getTemplateId() != null) {
            Template msg = messageTemplateRepo.findById(t.getTemplateId()).orElse(null);
            if (msg == null || !"APPROVED".equals(msg.getStatus())) {
                throw new IllegalStateException("The message template this reminder uses is no longer approved and can't be sent");
            }
        }

        Reminder r = Reminder.builder()
                .workspaceId(workspaceId)
                .name(t.getName())
                .reminderTemplateId(t.getId())
                .uploadId(uploadId)
                .templateId(t.getTemplateId())
                .customBody(t.getCustomBody())
                .kind(t.getKind() != null ? t.getKind() : "CUSTOM")
                .triggerDays(t.getTriggerDays())
                .status("PENDING_APPROVAL")
                .createdBy(WorkspaceContext.currentUserId())
                .build();
        Reminder saved = reminderRepo.save(r);
        log.info("Reminder run created: id={}, template={}, triggerDays={}", saved.getId(), t.getId(), t.getTriggerDays());
        return saved;
    }

    @Transactional
    public Reminder approve(UUID workspaceId, UUID reminderId, String note) {
        Reminder r = getById(workspaceId, reminderId);
        if (!"PENDING_APPROVAL".equals(r.getStatus())) {
            throw new IllegalStateException("Reminder is not awaiting approval: " + r.getStatus());
        }
        recordApproval(r, r.getStatus(), "APPROVED", note);
        r.setStatus("APPROVED");
        Reminder saved = reminderRepo.save(r);
        // Approval is what actually triggers this send — mirrors approving an
        // INSTANT campaign, which queues it immediately.
        fireReminder(saved);
        log.info("Reminder run approved and fired: id={}", reminderId);
        return saved;
    }

    @Transactional
    public Reminder reject(UUID workspaceId, UUID reminderId, String note) {
        Reminder r = getById(workspaceId, reminderId);
        if (!"PENDING_APPROVAL".equals(r.getStatus())) {
            throw new IllegalStateException("Reminder is not awaiting approval: " + r.getStatus());
        }
        recordApproval(r, r.getStatus(), "CANCELLED", note);
        r.setStatus("CANCELLED");
        r.setCancelledAt(Instant.now());
        Reminder saved = reminderRepo.save(r);
        log.info("Reminder run rejected: id={}", reminderId);
        return saved;
    }

    private void recordApproval(Reminder r, String fromState, String toState, String note) {
        Approval approval = Approval.builder()
                .workspaceId(r.getWorkspaceId())
                .reminderId(r.getId())
                .fromState(fromState)
                .toState(toState)
                .actorUserId(WorkspaceContext.currentUserId())
                .note(note)
                .createdAt(Instant.now())
                .build();
        approvalRepo.save(approval);
    }

    @Transactional(readOnly = true)
    public List<Reminder> list(UUID workspaceId, String status) {
        // Super admin has no workspace context (workspaceId == null): show every
        // workspace's reminder runs, mirroring campaigns/templates.
        boolean hasStatus = status != null && !status.isBlank();
        if (workspaceId == null) {
            return hasStatus
                    ? reminderRepo.findByStatusOrderByCreatedAtDesc(status)
                    : reminderRepo.findAllByOrderByCreatedAtDesc();
        }
        if (hasStatus) {
            return reminderRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(workspaceId, status);
        }
        return reminderRepo.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    @Transactional(readOnly = true)
    public Reminder getById(UUID workspaceId, UUID reminderId) {
        Reminder r = reminderRepo.findById(reminderId)
                .orElseThrow(() -> new IllegalArgumentException("Reminder not found: " + reminderId));
        if (!r.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalStateException("Reminder does not belong to this workspace");
        }
        return r;
    }

    private void fireReminder(Reminder r) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("reminderId",  r.getId().toString());
        payload.put("workspaceId", r.getWorkspaceId().toString());
        payload.put("triggerDays", r.getTriggerDays());
        if (r.getTemplateId() != null)       payload.put("templateId",        r.getTemplateId().toString());
        if (r.getCustomBody() != null)       payload.put("customBody",         r.getCustomBody());
        if (r.getRecipientGroupId() != null) payload.put("recipientGroupId",   r.getRecipientGroupId().toString());
        if (r.getUploadId() != null)         payload.put("uploadId",           r.getUploadId().toString());
        payload.put("kind", r.getKind());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize reminder payload", e);
        }

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("reminder")
                .aggregateId(r.getId())
                .eventType("ReminderFire")
                .payload(payloadJson)
                .createdAt(Instant.now())
                .build();
        outboxRepo.save(event);
        // A run is one-shot: once fired it's done (FIRED is terminal). Sending
        // again means creating a new run from the template, not re-firing this
        // one.
        r.setStatus("FIRED");
        r.setFiredAt(Instant.now());
        log.info("Reminder run OutboxEvent created: id={}, triggerDays={}", r.getId(), r.getTriggerDays());
    }
}
