package et.com.cog.esms.core.schedule;

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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reminder / schedule service.
 *
 * Responsibilities:
 *  - CRUD operations for schedule records
 *  - @Scheduled polling job: every minute, fire any PENDING schedules whose
 *    due date has arrived and publish an OutboxEvent so esms-sender dispatches the SMS.
 *
 * Reference: LLD §4.5, §8 (auto-scheduler)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Create a new reminder schedule rule.
     */
    @Transactional
    public Schedule create(UUID workspaceId, String name, UUID recipientGroupId,
                           UUID uploadId, UUID templateId, String customBody, String kind, boolean sendNow) {
        Schedule s = Schedule.builder()
                .workspaceId(workspaceId)
                .name(name)
                .recipientGroupId(recipientGroupId)
                .uploadId(uploadId)
                .templateId(templateId)
                .customBody(customBody)
                .kind(kind != null ? kind : "CUSTOM")
                .status(sendNow ? "ACTIVE" : "INACTIVE")
                .build();
        Schedule saved = scheduleRepo.save(s);
        log.info("Schedule created: id={}, name={}", saved.getId(), name);
        if (sendNow) {
            fireSchedule(saved);
        }
        return saved;
    }

    /**
     * List schedules for a workspace, optionally filtered by status.
     */
    @Transactional(readOnly = true)
    public List<Schedule> list(UUID workspaceId, String status) {
        if (status != null && !status.isBlank()) {
            return scheduleRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(workspaceId, status);
        }
        return scheduleRepo.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /**
     * Retrieve a single schedule, verifying workspace ownership.
     */
    @Transactional(readOnly = true)
    public Schedule getById(UUID workspaceId, UUID scheduleId) {
        Schedule s = scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found: " + scheduleId));
        if (!s.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalStateException("Schedule does not belong to this workspace");
        }
        return s;
    }

    /**
     * Deactivate a schedule.
     */
    @Transactional
    public Schedule deactivate(UUID workspaceId, UUID scheduleId) {
        Schedule s = getById(workspaceId, scheduleId);
        s.setStatus("INACTIVE");
        log.info("Schedule deactivated: id={}", scheduleId);
        return scheduleRepo.save(s);
    }

    /**
     * Activate a schedule.
     */
    @Transactional
    public Schedule activate(UUID workspaceId, UUID scheduleId) {
        Schedule s = getById(workspaceId, scheduleId);
        s.setStatus("ACTIVE");
        log.info("Schedule activated: id={}", scheduleId);
        return scheduleRepo.save(s);
    }

    /**
     * Trigger a schedule manually.
     */
    @Transactional
    public void trigger(UUID workspaceId, UUID scheduleId) {
        Schedule s = getById(workspaceId, scheduleId);
        fireSchedule(s);
    }

    // ── Scheduled polling job ─────────────────────────────────────────────────

    /**
     * Runs every 60 seconds. Finds all ACTIVE schedules and publishes an OutboxEvent
     * per schedule so esms-sender can check rules and fire necessary SMS.
     */
    @Scheduled(fixedDelayString = "${esms.scheduler.reminder-poll-ms:60000}")
    @Transactional
    public void processDueSchedules() {
        List<Schedule> active = scheduleRepo.findActiveSchedules();
        if (active.isEmpty()) return;

        log.info("Reminder scheduler: processing {} active schedules", active.size());
        for (Schedule s : active) {
            try {
                fireSchedule(s);
            } catch (Exception ex) {
                log.error("Failed to fire schedule id={}: {}", s.getId(), ex.getMessage(), ex);
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void fireSchedule(Schedule s) {
        // Build a minimal payload for esms-sender to resolve recipient list
        // and template, then dispatch the SMS.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("scheduleId", s.getId().toString());
        payload.put("workspaceId", s.getWorkspaceId().toString());
        if (s.getTemplateId() != null) payload.put("templateId", s.getTemplateId().toString());
        if (s.getCustomBody() != null) payload.put("customBody", s.getCustomBody());
        if (s.getRecipientGroupId() != null) payload.put("recipientGroupId", s.getRecipientGroupId().toString());
        if (s.getUploadId() != null) payload.put("uploadId", s.getUploadId().toString());
        payload.put("kind", s.getKind());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize schedule payload", e);
        }

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("schedule")
                .aggregateId(s.getId())
                .eventType("ReminderFire")
                .payload(payloadJson)
                .build();
        outboxRepo.save(event);

        log.info("Schedule event generated: id={}", s.getId());
    }
}
