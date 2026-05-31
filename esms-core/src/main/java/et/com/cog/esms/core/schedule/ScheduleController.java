package et.com.cog.esms.core.schedule;

import et.com.cog.esms.core.security.WorkspaceContext;
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
                req.getPolicyId(),
                req.getKind(),
                req.getDueDate(),
                req.getTemplateId());
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

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCHEDULE_MANAGE')")
    public ResponseEntity<ScheduleDto> cancel(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return ResponseEntity.ok(toDto(scheduleService.cancel(wsId, id)));
    }

    // ── DTO mapping ──────────────────────────────────────────────────────────

    private ScheduleDto toDto(Schedule s) {
        return new ScheduleDto(
                s.getId(),
                s.getWorkspaceId(),
                s.getPolicyId(),
                s.getKind(),
                s.getDueDate(),
                s.getTemplateId(),
                s.getStatus(),
                s.getFiredAt(),
                s.getCancelledAt(),
                s.getCreatedAt()
        );
    }

    // ── Inner DTOs ───────────────────────────────────────────────────────────

    @Data
    public static class CreateScheduleRequest {
        @NotNull private UUID      policyId;
        @NotNull private String    kind;       // T_MINUS_30 | T_MINUS_10 | CUSTOM
        @NotNull private LocalDate dueDate;
        @NotNull private UUID      templateId;
    }

    record ScheduleDto(
            UUID      id,
            UUID      workspaceId,
            UUID      policyId,
            String    kind,
            LocalDate dueDate,
            UUID      templateId,
            String    status,
            Instant   firedAt,
            Instant   cancelledAt,
            Instant   createdAt
    ) {}
}
