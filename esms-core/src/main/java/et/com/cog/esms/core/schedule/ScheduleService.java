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
     * Create a new reminder schedule.
     */
    @Transactional
    public Schedule create(UUID workspaceId, UUID policyId, String kind,
                           LocalDate dueDate, UUID templateId) {
        Schedule s = Schedule.builder()
                .workspaceId(workspaceId)
                .policyId(policyId)
                .kind(kind)
                .dueDate(dueDate)
                .templateId(templateId)
                .status("PENDING")
                .build();
        Schedule saved = scheduleRepo.save(s);
        log.info("Schedule created: id={}, kind={}, dueDate={}", saved.getId(), kind, dueDate);
        return saved;
    }

    /**
     * List schedules for a workspace, optionally filtered by status.
     */
    @Transactional(readOnly = true)
    public List<Schedule> list(UUID workspaceId, String status) {
        if (status != null && !status.isBlank()) {
            return scheduleRepo.findByWorkspaceIdAndStatusOrderByDueDateAsc(workspaceId, status);
        }
        return scheduleRepo.findByWorkspaceIdOrderByDueDateAsc(workspaceId);
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
     * Cancel a PENDING schedule.
     */
    @Transactional
    public Schedule cancel(UUID workspaceId, UUID scheduleId) {
        Schedule s = getById(workspaceId, scheduleId);
        if (!"PENDING".equals(s.getStatus())) {
            throw new IllegalStateException(
                    "Only PENDING schedules can be cancelled. Current status: " + s.getStatus());
        }
        s.setStatus("CANCELLED");
        s.setCancelledAt(Instant.now());
        log.info("Schedule cancelled: id={}", scheduleId);
        return scheduleRepo.save(s);
    }

    // ── Scheduled polling job ─────────────────────────────────────────────────

    /**
     * Runs every 60 seconds. Finds all PENDING schedules whose due date has
     * arrived or passed, marks them FIRED, and publishes an OutboxEvent per
     * schedule so esms-sender picks them up via the existing relay loop.
     */
    @Scheduled(fixedDelayString = "${esms.scheduler.reminder-poll-ms:60000}")
    @Transactional
    public void processDueSchedules() {
        LocalDate today = LocalDate.now();
        List<Schedule> due = scheduleRepo.findPendingDueBy(today);
        if (due.isEmpty()) return;

        log.info("Reminder scheduler: found {} due schedule(s) for {}", due.size(), today);
        for (Schedule s : due) {
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
        Map<String, Object> payload = Map.of(
                "scheduleId",  s.getId().toString(),
                "workspaceId", s.getWorkspaceId().toString(),
                "policyId",    s.getPolicyId().toString(),
                "templateId",  s.getTemplateId().toString(),
                "kind",        s.getKind()
        );

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

        s.setStatus("FIRED");
        s.setFiredAt(Instant.now());
        scheduleRepo.save(s);

        log.info("Schedule fired: id={}, policyId={}, templateId={}",
                s.getId(), s.getPolicyId(), s.getTemplateId());
    }
}
