package et.com.cog.esms.core.reminder;

import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reminder REST controller.
 *
 * A "reminder" uploads an Excel file with insurance holders + expiry dates.
 * The system checks daily and instantly sends an SMS to any holder whose
 * insurance expires in exactly {@code triggerDays} days.
 *
 * This is distinct from a "scheduled" campaign (kind=SCHEDULED), which sends
 * a normal SMS batch at a specific future date/time.
 *
 * Endpoints:
 *   POST   /reminders              — create a reminder rule
 *   GET    /reminders              — list (optional ?status=ACTIVE|INACTIVE)
 *   GET    /reminders/{id}         — get a single reminder rule
 *   POST   /reminders/{id}/activate   — re-enable a paused rule
 *   POST   /reminders/{id}/deactivate — pause a rule
 *   POST   /reminders/{id}/trigger    — manually fire now (bypass daily schedule)
 *
 * Reference: LLD §4.5
 */
@Slf4j
@RestController
@RequestMapping("/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Create a new reminder rule.
     *
     * Example request body:
     * <pre>
     * {
     *   "name": "15-Day Policy Expiry Reminder",
     *   "uploadId": "...",
     *   "templateId": "...",
     *   "triggerDays": 15,
     *   "kind": "CUSTOM"
     * }
     * </pre>
     */
    @PostMapping
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<ReminderDto> create(@Valid @RequestBody CreateReminderRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        Reminder r = reminderService.create(
                wsId,
                req.getName(),
                req.getRecipientGroupId(),
                req.getUploadId(),
                req.getTemplateId(),
                req.getCustomBody(),
                req.getKind(),
                req.getTriggerDays());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(r));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<ReminderDto> update(@PathVariable UUID id,
                                               @RequestBody java.util.Map<String, Object> updates) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        Reminder r = reminderService.update(wsId, id, updates);
        return ResponseEntity.ok(toDto(r));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<List<ReminderDto>> list(
            @RequestParam(required = false) String status) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<ReminderDto> result = reminderService.list(wsId, status)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // ── Get by ID ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<ReminderDto> getById(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return ResponseEntity.ok(toDto(reminderService.getById(wsId, id)));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<ReminderDto> deactivate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return ResponseEntity.ok(toDto(reminderService.deactivate(wsId, id)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<ReminderDto> activate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return ResponseEntity.ok(toDto(reminderService.activate(wsId, id)));
    }

    /**
     * Manually fire this reminder immediately (for testing or ad-hoc sends).
     * Finds all contacts in the upload whose expiry == today + triggerDays, then sends SMS.
     */
    @PostMapping("/{id}/trigger")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> triggerNow(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        reminderService.triggerNow(wsId, id);
        return ResponseEntity.accepted().build();
    }

    // ── DTO mapping ───────────────────────────────────────────────────────────

    private ReminderDto toDto(Reminder r) {
        return new ReminderDto(
                r.getId(),
                r.getWorkspaceId(),
                r.getName(),
                r.getRecipientGroupId(),
                r.getUploadId(),
                r.getTemplateId(),
                r.getCustomBody(),
                r.getTriggerDays(),
                r.getKind(),
                r.getStatus(),
                r.getCreatedAt()
        );
    }

    // ── Inner DTOs ────────────────────────────────────────────────────────────

    @Data
    public static class CreateReminderRequest {

        @NotBlank
        private String name;

        private UUID recipientGroupId;

        /** ID of the Excel upload containing policy holders + expiry dates. */
        private UUID uploadId;

        private UUID templateId;

        private String customBody;

        /**
         * Days before expiry to trigger the reminder.
         * E.g. 15 = "send SMS when 15 days remain before insurance expires".
         * Must be at least 1.
         */
        @NotNull
        @Min(1)
        private Integer triggerDays;

        /** T_MINUS_30 | T_MINUS_10 | CUSTOM */
        private String kind;
    }

    record ReminderDto(
            UUID    id,
            UUID    workspaceId,
            String  name,
            UUID    recipientGroupId,
            UUID    uploadId,
            UUID    templateId,
            String  customBody,
            Integer triggerDays,
            String  kind,
            String  status,
            Instant createdAt
    ) {}
}
