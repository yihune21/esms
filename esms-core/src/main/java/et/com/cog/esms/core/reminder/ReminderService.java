package et.com.cog.esms.core.reminder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.core.messaging.OutboxEvent;
import et.com.cog.esms.core.messaging.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reminder service.
 *
 * Responsibilities:
 *  - CRUD operations for reminder rules.
 *  - Daily @Scheduled polling job: for every ACTIVE reminder, publishes an OutboxEvent
 *    that instructs esms-sender to find all contacts in the linked upload whose insurance
 *    expiry date is exactly {@code triggerDays} days from today, then send each an SMS.
 *
 * A "reminder" is NOT a scheduled SMS. It is an instant SMS triggered automatically
 * when a date-based condition is met (e.g. "15 days before policy expiry").
 *
 * For sending an SMS at a specific future date, use a Campaign with kind=SCHEDULED.
 *
 * Reference: LLD §4.5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepository reminderRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Create a new reminder rule.
     *
     * @param triggerDays Number of days before insurance expiry to trigger the SMS.
     *                    E.g. 15 means "send when exactly 15 days remain".
     * @param kind        T_MINUS_30 | T_MINUS_10 | CUSTOM
     */
    @Transactional
    public Reminder create(UUID workspaceId, String name, UUID recipientGroupId,
                           UUID uploadId, UUID templateId, String customBody,
                           String kind, int triggerDays) {
        Reminder r = Reminder.builder()
                .workspaceId(workspaceId)
                .name(name)
                .recipientGroupId(recipientGroupId)
                .uploadId(uploadId)
                .templateId(templateId)
                .customBody(customBody)
                .kind(kind != null ? kind : "CUSTOM")
                .triggerDays(triggerDays)
                .status("ACTIVE")
                .build();
        Reminder saved = reminderRepo.save(r);
        log.info("Reminder created: id={}, name={}, triggerDays={}", saved.getId(), name, triggerDays);
        return saved;
    }

    /**
     * List reminder rules for a workspace, optionally filtered by status.
     */
    @Transactional(readOnly = true)
    public List<Reminder> list(UUID workspaceId, String status) {
        if (status != null && !status.isBlank()) {
            return reminderRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(workspaceId, status);
        }
        return reminderRepo.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /**
     * Retrieve a single reminder, verifying workspace ownership.
     */
    @Transactional(readOnly = true)
    public Reminder getById(UUID workspaceId, UUID reminderId) {
        Reminder r = reminderRepo.findById(reminderId)
                .orElseThrow(() -> new IllegalArgumentException("Reminder not found: " + reminderId));
        if (!r.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalStateException("Reminder does not belong to this workspace");
        }
        return r;
    }

    /**
     * Deactivate a reminder rule — no more daily evaluations until re-activated.
     */
    @Transactional
    public Reminder deactivate(UUID workspaceId, UUID reminderId) {
        Reminder r = getById(workspaceId, reminderId);
        r.setStatus("INACTIVE");
        log.info("Reminder deactivated: id={}", reminderId);
        return reminderRepo.save(r);
    }

    /**
     * Activate a reminder rule — resumes daily evaluation.
     */
    @Transactional
    public Reminder activate(UUID workspaceId, UUID reminderId) {
        Reminder r = getById(workspaceId, reminderId);
        r.setStatus("ACTIVE");
        log.info("Reminder activated: id={}", reminderId);
        return reminderRepo.save(r);
    }

    /**
     * Manually trigger a reminder right now (bypasses the daily schedule).
     * Useful for testing or one-off sends.
     */
    @Transactional
    public void triggerNow(UUID workspaceId, UUID reminderId) {
        Reminder r = getById(workspaceId, reminderId);
        fireReminder(r);
        log.info("Reminder manually triggered: id={}", reminderId);
    }

    // ── Daily polling job ─────────────────────────────────────────────────────

    /**
     * Runs once per day (configurable). For each ACTIVE reminder rule, publishes an
     * OutboxEvent so esms-sender can:
     *   1. Load the linked upload (Excel) rows.
     *   2. Find contacts whose expiry date == today + triggerDays.
     *   3. Send an SMS to each matching contact.
     *
     * The actual date-matching logic lives in esms-sender so that it has direct access
     * to the uploaded file data and the message dispatch pipeline.
     */
    @Scheduled(cron = "${esms.scheduler.reminder-cron:0 0 8 * * *}")
    @Transactional
    public void processDueReminders() {
        List<Reminder> active = reminderRepo.findActiveReminders();
        if (active.isEmpty()) return;

        log.info("Reminder scheduler: evaluating {} active reminder rules", active.size());
        for (Reminder r : active) {
            try {
                fireReminder(r);
            } catch (Exception ex) {
                log.error("Failed to fire reminder id={}: {}", r.getId(), ex.getMessage(), ex);
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void fireReminder(Reminder r) {
        // Build payload for esms-sender.
        // esms-sender will: load the upload, find rows where (expiryDate - today == triggerDays),
        // and dispatch an SMS for each such contact using the specified template.
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
                .build();
        outboxRepo.save(event);

        log.info("Reminder OutboxEvent created: id={}, triggerDays={}", r.getId(), r.getTriggerDays());
    }
}
