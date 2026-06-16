package et.com.cog.esms.core.schedule;

import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reminder / schedule REST controller.
 *
 * POST   /schedules              — create a reminder
 * GET    /schedules              — list (optional ?status=PENDING|FIRED|CANCELLED)
 * GET    /schedules/{id}         — get single schedule
 * POST   /schedules/{id}/cancel  — cancel a PENDING schedule
 *
 * Reference: LLD §4.5, §8
 */
@Slf4j
@RestController
@RequestMapping("/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<ScheduleDto> create(@Valid @RequestBody CreateScheduleRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        Schedule s = scheduleService.create(
                wsId,
                req.getName(),
                req.getRecipientGroupId(),
                req.getUploadId(),
                req.getTemplateId(),
                req.getCustomBody(),
                req.getKind(),
                req.isSendNow());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(s));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<List<ScheduleDto>> list(
            @RequestParam(required = false) String status) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<ScheduleDto> result = scheduleService.list(wsId, status)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('SCHEDULE_VIEW','SCHEDULE_MANAGE')")
    public ResponseEntity<ScheduleDto> getById(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return ResponseEntity.ok(toDto(scheduleService.getById(wsId, id)));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<ScheduleDto> deactivate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return ResponseEntity.ok(toDto(scheduleService.deactivate(wsId, id)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<ScheduleDto> activate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return ResponseEntity.ok(toDto(scheduleService.activate(wsId, id)));
    }

    @PostMapping("/{id}/trigger")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<?> trigger(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        scheduleService.trigger(wsId, id);
        return ResponseEntity.accepted().build();
    }

    // ── DTO mapping ──────────────────────────────────────────────────────────

    private ScheduleDto toDto(Schedule s) {
        return new ScheduleDto(
                s.getId(),
                s.getWorkspaceId(),
                s.getName(),
                s.getRecipientGroupId(),
                s.getUploadId(),
                s.getTemplateId(),
                s.getCustomBody(),
                s.getKind(),
                s.getStatus(),
                s.getCreatedAt()
        );
    }

    // ── Inner DTOs ───────────────────────────────────────────────────────────

    @Data
    public static class CreateScheduleRequest {
        @NotBlank private String name;
        private UUID      recipientGroupId;
        private UUID      uploadId;
        private UUID      templateId;
        private String    customBody;
        private String    kind;       // T_MINUS_30 | T_MINUS_10 | CUSTOM
        private boolean   sendNow;
    }

    record ScheduleDto(
            UUID      id,
            UUID      workspaceId,
            String    name,
            UUID      recipientGroupId,
            UUID      uploadId,
            UUID      templateId,
            String    customBody,
            String    kind,
            String    status,
            Instant   createdAt
    ) {}
}
