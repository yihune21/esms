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

@Slf4j
@RestController
@RequestMapping("/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;

    
    @PostMapping
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateReminderRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "No workspace context — select a workspace before managing reminders"));
        }
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
    public ResponseEntity<?> update(@PathVariable UUID id,
                                               @RequestBody java.util.Map<String, Object> updates) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "No workspace context — select a workspace before managing reminders"));
        }
        Reminder r = reminderService.update(wsId, id, updates);
        return ResponseEntity.ok(toDto(r));
    }


    @GetMapping
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<?> list(
            @RequestParam(required = false) String status) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "No workspace context — select a workspace before viewing reminders"));
        }
        List<ReminderDto> result = reminderService.list(wsId, status)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }


    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<?> getById(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "No workspace context"));
        }
        return ResponseEntity.ok(toDto(reminderService.getById(wsId, id)));
    }


    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> deactivate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "No workspace context"));
        }
        return ResponseEntity.ok(toDto(reminderService.deactivate(wsId, id)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> activate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "No workspace context"));
        }
        return ResponseEntity.ok(toDto(reminderService.activate(wsId, id)));
    }

  
    @PostMapping("/{id}/trigger")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> triggerNow(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        if (wsId == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("title", "No workspace context"));
        }
        reminderService.triggerNow(wsId, id);
        return ResponseEntity.accepted().build();
    }


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


    @Data
    public static class CreateReminderRequest {

        @NotBlank
        private String name;

        private UUID recipientGroupId;

        private UUID uploadId;

        private UUID templateId;

        private String customBody;

   
        @NotNull
        @Min(1)
        private Integer triggerDays;

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
