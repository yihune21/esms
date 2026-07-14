package et.com.cog.esms.core.reminder;

import et.com.cog.esms.core.audit.AuditService;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/reminder-templates")
@RequiredArgsConstructor
public class ReminderTemplateController {

    private final ReminderTemplateService templateService;
    private final AuditService auditService;

    @PostMapping
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateReminderTemplateRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("title", "No workspace context — select a workspace before managing reminders"));
        }
        ReminderTemplate t = templateService.create(wsId, req.getName(), req.getTemplateId(),
                req.getCustomBody(), req.getKind(), req.getTriggerDays());
        auditService.log(wsId, "REMINDER", "INFO", "REMINDER_TEMPLATE_CREATED", "ReminderTemplate", t.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(t));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody Map<String, Object> updates) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "No workspace context"));
        }
        ReminderTemplate t = templateService.update(wsId, id, updates);
        auditService.log(wsId, "REMINDER", "INFO", "REMINDER_TEMPLATE_UPDATED", "ReminderTemplate", t.getId());
        return ResponseEntity.ok(toDto(t));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<?> list(@RequestParam(required = false) String status) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "No workspace context"));
        }
        List<ReminderTemplateDto> result = templateService.list(wsId, status)
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
        return ResponseEntity.ok(toDto(templateService.getById(wsId, id)));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> deactivate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "No workspace context"));
        }
        auditService.log(wsId, "REMINDER", "INFO", "REMINDER_TEMPLATE_DEACTIVATED", "ReminderTemplate", id);
        return ResponseEntity.ok(toDto(templateService.deactivate(wsId, id)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> activate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest().body(Map.of("title", "No workspace context"));
        }
        auditService.log(wsId, "REMINDER", "INFO", "REMINDER_TEMPLATE_ACTIVATED", "ReminderTemplate", id);
        return ResponseEntity.ok(toDto(templateService.activate(wsId, id)));
    }

    private ReminderTemplateDto toDto(ReminderTemplate t) {
        return new ReminderTemplateDto(
                t.getId(), t.getWorkspaceId(), t.getName(), t.getCustomBody(),
                t.getTemplateId(), t.getTriggerDays(), t.getKind(), t.getStatus(),
                t.getCreatedBy(), t.getCreatedAt());
    }

    @Data
    public static class CreateReminderTemplateRequest {
        @NotBlank private String name;
        private UUID templateId;
        private String customBody;
        @NotNull @Min(1) private Integer triggerDays;
        private String kind;
    }

    record ReminderTemplateDto(
            UUID    id,
            UUID    workspaceId,
            String  name,
            String  customBody,
            UUID    templateId,
            Integer triggerDays,
            String  kind,
            String  status,
            UUID    createdBy,
            Instant createdAt
    ) {}
}
