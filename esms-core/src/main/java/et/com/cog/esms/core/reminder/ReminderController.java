package et.com.cog.esms.core.reminder;

import et.com.cog.esms.core.audit.AuditService;
import et.com.cog.esms.core.contact.Contact;
import et.com.cog.esms.core.contact.ContactRepository;
import et.com.cog.esms.core.messaging.Message;
import et.com.cog.esms.core.messaging.MessageRepository;
import et.com.cog.esms.core.messaging.MessageRetryService;
import et.com.cog.esms.core.security.WorkspaceContext;
import et.com.cog.esms.core.template.Template;
import et.com.cog.esms.core.template.TemplateRecipientRepository;
import et.com.cog.esms.core.template.TemplateRepository;
import jakarta.validation.Valid;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A "reminder" at this endpoint is a RUN — one send of a reminder template
 * against uploaded policy data, created every time Send is pressed. Every run
 * is approval-gated (approve() fires it). The reusable template lives at
 * /reminder-templates.
 */
@Slf4j
@RestController
@RequestMapping("/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;
    private final MessageRepository messageRepo;
    private final ContactRepository contactRepo;
    private final TemplateRepository templateRepo;
    private final TemplateRecipientRepository templateRecipientRepo;
    private final MessageRetryService retryService;
    private final AuditService auditService;

    // Send: create a run from a reminder template + an uploaded dataset.
    @PostMapping
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateRunRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            auditService.log(null, "REMINDER", "WARN", "REMINDER_CREATE_BAD_REQUEST", "Reminder", null);
            return ResponseEntity.badRequest()
                    .body(Map.of("title", "No workspace context — select a workspace before sending reminders"));
        }
        try {
            Reminder r = reminderService.createRun(wsId, req.getReminderTemplateId(), req.getUploadId());
            auditService.log(wsId, "REMINDER", "INFO", "REMINDER_RUN_CREATED", "Reminder", r.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(r));
        } catch (IllegalStateException e) {
            auditService.log(wsId, "REMINDER", "WARN", "REMINDER_RUN_REJECTED_STATE", "Reminder", null);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("title", e.getMessage()));
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<?> list(@RequestParam(required = false) String status) {
        // A super admin has no workspace context; pass null through so the
        // service returns every workspace's runs (mirrors campaigns/templates).
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<ReminderDto> result = reminderService.list(wsId, status)
                .stream().map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<?> getById(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "No workspace context"));
        }
        return ResponseEntity.ok(toDto(reminderService.getById(wsId, id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('SCHEDULE_APPROVE')")
    public ResponseEntity<?> approve(@PathVariable UUID id, @RequestBody(required = false) NoteRequest note) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "No workspace context"));
        }
        try {
            Reminder r = reminderService.approve(wsId, id, note != null ? note.getNote() : null);
            auditService.log(wsId, "REMINDER", "INFO", "REMINDER_APPROVED", "Reminder", id);
            return ResponseEntity.ok(toDto(r));
        } catch (IllegalStateException e) {
            auditService.log(wsId, "REMINDER", "WARN", "REMINDER_APPROVE_REJECTED_STATE", "Reminder", id);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("title", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('SCHEDULE_APPROVE')")
    public ResponseEntity<?> reject(@PathVariable UUID id, @RequestBody(required = false) NoteRequest note) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "No workspace context"));
        }
        try {
            Reminder r = reminderService.reject(wsId, id, note != null ? note.getNote() : null);
            auditService.log(wsId, "REMINDER", "WARN", "REMINDER_REJECTED", "Reminder", id);
            return ResponseEntity.ok(toDto(r));
        } catch (IllegalStateException e) {
            auditService.log(wsId, "REMINDER", "WARN", "REMINDER_REJECT_REJECTED_STATE", "Reminder", id);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("title", e.getMessage()));
        }
    }

    // Recipients this run messaged (once fired), each carrying its per-message
    // delivery status so the UI can show exactly which numbers delivered or
    // failed. Pre-fire, falls back to the upload's contacts (status null).
    @GetMapping("/{id}/recipients")
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<?> recipients(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "No workspace context"));
        }
        Reminder r = reminderService.getById(wsId, id); // 404s / workspace-checks the run
        List<Message> messages = messageRepo.findByReminderId(id);
        if (!messages.isEmpty()) {
            Map<UUID, String> namesByContact = contactRepo
                    .findAllById(messages.stream().map(Message::getContactId).filter(c -> c != null).collect(Collectors.toList()))
                    .stream()
                    .collect(Collectors.toMap(Contact::getId, Contact::getName, (a, b) -> a));
            List<RecipientDto> result = messages.stream()
                    .map(m -> new RecipientDto(m.getId(), m.getToNumber(),
                            m.getContactId() != null ? namesByContact.get(m.getContactId()) : null,
                            m.getStatus(), m.getErrorCode()))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(resolvePreDispatchRecipients(
                r.getRecipientGroupId(), r.getUploadId(), r.getTemplateId()));
    }

    // Manual retry for messages this run sent that terminally failed
    // (FAILED/EXPIRED) — one recipient if a messageId is given, otherwise all
    // failed ones. The run itself stays FIRED; no re-approval is required
    // because only what the original approval covered is re-sent.
    @PostMapping("/{id}/retry-failed")
    @PreAuthorize("hasAnyAuthority('SCHEDULE_MANAGE','SCHEDULE_APPROVE')")
    public ResponseEntity<?> retryFailed(@PathVariable UUID id,
                                         @RequestBody(required = false) RetryRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "No workspace context"));
        }
        Reminder r = reminderService.getById(wsId, id);

        List<Message> candidates;
        if (req != null && req.getMessageId() != null) {
            Message m = messageRepo.findById(req.getMessageId()).orElse(null);
            if (m == null || !id.equals(m.getReminderId())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("title", "Message does not belong to this reminder"));
            }
            if (!MessageRetryService.isRetryable(m)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("title", "Only failed messages can be retried"));
            }
            if (req.getNewNumber() != null && !req.getNewNumber().isBlank()) {
                retryService.retryToNewNumber(m, req.getNewNumber(),
                        r.getTemplateId(), r.getWorkspaceId(), null);
                auditService.log(wsId, "REMINDER", "INFO", "REMINDER_RETRY_NEW_NUMBER", "Reminder", id);
                return ResponseEntity.ok(Map.of("retried", 1, "reminder", toDto(r)));
            }
            candidates = List.of(m);
        } else {
            candidates = messageRepo.findByReminderId(id).stream()
                    .filter(MessageRetryService::isRetryable)
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("title", "This reminder has no failed messages to retry"));
            }
        }

        int retried = retryService.retryMessages(candidates,
                r.getTemplateId(), r.getWorkspaceId(), null);

        auditService.log(wsId, "REMINDER", "INFO", "REMINDER_RETRY_FAILED", "Reminder", id);

        return ResponseEntity.ok(Map.of("retried", retried, "reminder", toDto(r)));
    }

    private List<RecipientDto> resolvePreDispatchRecipients(UUID groupId, UUID uploadId, UUID templateId) {
        if (groupId != null) {
            return contactRepo.findActiveByGroupId(groupId).stream()
                    .map(c -> new RecipientDto(null, c.getPhoneE164(), c.getName(), null, null))
                    .collect(Collectors.toList());
        }
        if (uploadId != null) {
            return contactRepo.findByUploadIdAndStatus(uploadId, "ACTIVE").stream()
                    .map(c -> new RecipientDto(null, c.getPhoneE164(), c.getName(), null, null))
                    .collect(Collectors.toList());
        }
        if (templateId != null) {
            Template tmpl = templateRepo.findById(templateId).orElse(null);
            if (tmpl != null) {
                if (tmpl.getRecipientGroupId() != null) {
                    return contactRepo.findActiveByGroupId(tmpl.getRecipientGroupId()).stream()
                            .map(c -> new RecipientDto(null, c.getPhoneE164(), c.getName(), null, null))
                            .collect(Collectors.toList());
                }
                return templateRecipientRepo.findByTemplateId(tmpl.getId()).stream()
                        .map(rec -> new RecipientDto(null, rec.getPhoneE164(), rec.getName(), null, null))
                        .collect(Collectors.toList());
            }
        }
        return List.of();
    }

    private ReminderDto toDto(Reminder r) {
        long delivered = messageRepo.countByReminderIdAndStatusIn(r.getId(), List.of("DELIVERED"));
        long failed    = messageRepo.countByReminderIdAndStatusIn(r.getId(), List.of("FAILED"));
        long sent      = messageRepo.countByReminderIdAndStatusIn(r.getId(), List.of("SENT"));
        long total     = messageRepo.countByReminderId(r.getId());
        double rate    = (sent + delivered) > 0
                ? Math.round(1000.0 * delivered / (sent + delivered)) / 10.0 : 0.0;

        // A run that hasn't fired has no messages yet — estimate from its
        // upload so the UI never wrongly shows "no recipients".
        Integer recipientEstimate = null;
        if (r.getRecipientGroupId() != null) {
            recipientEstimate = contactRepo.findActiveByGroupId(r.getRecipientGroupId()).size();
        } else if (r.getUploadId() != null) {
            recipientEstimate = contactRepo.findByUploadIdAndStatus(r.getUploadId(), "ACTIVE").size();
        }

        return new ReminderDto(
                r.getId(), r.getWorkspaceId(), r.getName(),
                r.getReminderTemplateId(), r.getRecipientGroupId(), r.getUploadId(),
                r.getTemplateId(), r.getCustomBody(), r.getTriggerDays(), r.getKind(),
                r.getStatus(), r.getCreatedAt(),
                total, delivered, failed, rate, recipientEstimate);
    }

    @Data
    public static class CreateRunRequest {
        @NotNull private UUID reminderTemplateId;
        @NotNull private UUID uploadId;
    }

    @Data
    public static class NoteRequest {
        private String note;
    }

    record RecipientDto(UUID messageId, String phone, String name, String status, String errorCode) {}

    @Data
    public static class RetryRequest {
        private UUID messageId;
        // Single-message retry only: re-send to this corrected number.
        private String newNumber;
    }

    record ReminderDto(
            UUID    id,
            UUID    workspaceId,
            String  name,
            UUID    reminderTemplateId,
            UUID    recipientGroupId,
            UUID    uploadId,
            UUID    templateId,
            String  customBody,
            Integer triggerDays,
            String  kind,
            String  status,
            Instant createdAt,
            long    totalMessages,
            long    deliveredMessages,
            long    failedMessages,
            double  deliveryRatePct,
            Integer recipientEstimate
    ) {}
}
