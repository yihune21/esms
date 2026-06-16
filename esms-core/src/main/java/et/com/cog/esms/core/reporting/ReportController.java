package et.com.cog.esms.core.reporting;

import et.com.cog.esms.core.reporting.ReportService.CampaignSummaryDto;
import et.com.cog.esms.core.reporting.ReportService.DailyTrendDto;
import et.com.cog.esms.core.security.WorkspaceContext;
import et.com.cog.esms.core.identity.UserActivityLogRepository;
import et.com.cog.esms.core.identity.UserRepository;
import et.com.cog.esms.core.identity.WorkspaceMemberRepository;
import et.com.cog.esms.core.campaign.CampaignRepository;
import et.com.cog.esms.core.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reporting REST controller.
 *
 * GET  /reports/messages  — delivery dashboard with totals + filtered rows
 * POST /reports/exports   — request an async XLSX/CSV export
 * GET  /reports/exports/{id} — poll export status / retrieve file
 *
 * Reference: LLD §6.7
 */
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService              reportService;
    private final UserActivityLogRepository  activityLogRepo;
    private final UserRepository             userRepo;
    private final WorkspaceRepository        workspaceRepo;
    private final CampaignRepository         campaignRepo;
    private final et.com.cog.esms.core.messaging.MessageRepository messageRepo;
    private final WorkspaceMemberRepository  memberRepo;

    /**
     * Delivery dashboard.
     *
     * Query params:
     *   from        – ISO-8601 start timestamp (inclusive)
     *   to          – ISO-8601 end timestamp   (inclusive)
     *   campaignId  – filter by campaign UUID
     *   branch      – filter by branch tag
     *   status      – PENDING | QUEUED | SENT | DELIVERED | FAILED | EXPIRED
     *   page        – 0-based page index (default 0)
     *   size        – page size (default 20)
     */
    @GetMapping("/messages")
    @PreAuthorize("hasAnyAuthority('REPORT_VIEW','REPORT_EXPORT')")
    public ResponseEntity<DeliveryReport> getMessages(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID wsId = resolveWorkspace(workspaceId);
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        DeliveryReport report = reportService.getDeliveryReport(
                wsId, from, to, campaignId, branch, status, pageable);

        return ResponseEntity.ok(report);
    }

    /**
     * List all export jobs for the current workspace.
     */
    @GetMapping("/exports")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    public ResponseEntity<List<ExportStatusResponse>> listExports() {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<ExportStatusResponse> result = reportService.listExports(wsId).stream()
                .map(e -> new ExportStatusResponse(
                        e.getId(), e.getStatus(), e.getFormat(),
                        e.getCreatedAt(), e.getCompletedAt(), e.getFilePath()))
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Request an async export job.
     *
     * Body: { "filter": { "from": "…", "to": "…", "campaignId": "…" }, "format": "XLSX" }
     */
    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    public ResponseEntity<ExportResponse> requestExport(
            @RequestBody ReportExportRequest req) {

        UUID wsId = WorkspaceContext.currentWorkspaceId();
        ReportExport export = reportService.requestExport(wsId, req);

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(new ExportResponse(export.getId(), export.getStatus()));
    }

    /**
     * Poll export job status.
     * When status == DONE the response includes a filePath for download.
     */
    @GetMapping("/exports/{id}")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    public ResponseEntity<?> getExport(@PathVariable UUID id) {
        ReportExport export = reportService.getExport(id);
        if ("DONE".equals(export.getStatus()) && export.getFilePath() != null) {
            File file = new File(export.getFilePath());
            if (file.exists()) {
                Resource resource = new FileSystemResource(file);
                String contentType = "XLSX".equalsIgnoreCase(export.getFormat())
                        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        : "text/csv";
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                        .body(resource);
            }
        }
        return ResponseEntity.ok(new ExportStatusResponse(
                export.getId(),
                export.getStatus(),
                export.getFormat(),
                export.getCreatedAt(),
                export.getCompletedAt(),
                export.getFilePath()
        ));
    }

    // ── Aggregation endpoints ──────────────────────────────────────────────

    /**
     * Daily delivery trend.
     *
     * Returns an array of { day, status, total } points for the given date range.
     * Each (day × status) combination is one entry.
     *
     * Query params:
     *   from – ISO-8601 start timestamp (optional)
     *   to   – ISO-8601 end timestamp   (optional)
     */
    @GetMapping("/aggregations/daily")
    @PreAuthorize("hasAnyAuthority('REPORT_VIEW','REPORT_EXPORT')")
    public ResponseEntity<List<DailyTrendDto>> getDailyTrend(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) Instant to) {

        UUID wsId = resolveWorkspace(workspaceId);
        return ResponseEntity.ok(reportService.getDailyTrend(wsId, from, to));
    }

    /**
     * Per-campaign message summary.
     *
     * Returns one row per campaign with sent / delivered / failed / pending counts.
     *
     * Query params:
     *   from – ISO-8601 start timestamp (optional)
     *   to   – ISO-8601 end timestamp   (optional)
     */
    @GetMapping("/aggregations/campaigns")
    @PreAuthorize("hasAnyAuthority('REPORT_VIEW','REPORT_EXPORT')")
    public ResponseEntity<List<CampaignSummaryDto>> getCampaignSummaries(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) Instant to) {

        UUID wsId = resolveWorkspace(workspaceId);
        return ResponseEntity.ok(reportService.getCampaignSummaries(wsId, from, to));
    }

    // ── Dashboard summary ──────────────────────────────────────────────

    /**
     * Single dashboard-summary endpoint.
     * For SUPER_ADMIN with no workspaceId: returns platform-wide aggregates.
     * For workspace users (or SUPER_ADMIN with ?workspaceId=): returns per-workspace totals.
     *
     * Response shape:
     * {
     *   totalMessages, sentMessages, deliveredMessages, failedMessages,
     *   deliveryRatePct, totalCampaigns, activeWorkspaces, totalUsers,
     *   activeUsers30d
     * }
     */
    @GetMapping("/dashboard/summary")
    @PreAuthorize("hasAnyAuthority('REPORT_VIEW','REPORT_EXPORT')")
    public ResponseEntity<Map<String, Object>> getDashboardSummary(
            @RequestParam(required = false) UUID workspaceId) {

        UUID wsId = resolveWorkspace(workspaceId);

        long totalMessages    = messageRepo.countByWorkspaceIdAndStatus(wsId, "SENT")
                              + messageRepo.countByWorkspaceIdAndStatus(wsId, "DELIVERED")
                              + messageRepo.countByWorkspaceIdAndStatus(wsId, "FAILED")
                              + messageRepo.countByWorkspaceIdAndStatus(wsId, "PENDING")
                              + messageRepo.countByWorkspaceIdAndStatus(wsId, "QUEUED");
        long delivered        = messageRepo.countByWorkspaceIdAndStatus(wsId, "DELIVERED");
        long failed           = messageRepo.countByWorkspaceIdAndStatus(wsId, "FAILED");
        long sent             = messageRepo.countByWorkspaceIdAndStatus(wsId, "SENT");

        double deliveryRate   = (sent + delivered) > 0
                ? Math.round(1000.0 * delivered / (sent + delivered)) / 10.0 : 0.0;

        long totalCampaigns   = wsId == null
                ? campaignRepo.count()
                : campaignRepo.findByWorkspaceIdOrderByCreatedAtDesc(wsId).size();

        long activeWorkspaces = wsId == null
                ? workspaceRepo.findAll().stream().filter(w -> "ACTIVE".equals(w.getStatus())).count()
                : ("ACTIVE".equals(workspaceRepo.findById(wsId).map(w -> w.getStatus()).orElse("")) ? 1L : 0L);

        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        long activeUsers30d   = activityLogRepo.countActiveUsersSince(wsId, thirtyDaysAgo);

        long totalUsers       = wsId == null
                ? userRepo.count()
                : memberRepo.countByWorkspaceId(wsId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalMessages",    totalMessages);
        summary.put("sentMessages",     sent);
        summary.put("deliveredMessages",delivered);
        summary.put("failedMessages",   failed);
        summary.put("deliveryRatePct",  deliveryRate);
        summary.put("totalCampaigns",   totalCampaigns);
        summary.put("activeWorkspaces", activeWorkspaces);
        summary.put("activeUsers30d",   activeUsers30d);
        summary.put("totalUsers",       totalUsers);
        summary.put("workspaceId",      wsId);

        return ResponseEntity.ok(summary);
    }

    // ── Users & Access activity analytics ─────────────────────────────

    /**
     * Action frequency chart data for Reports → Users &amp; Access tab.
     * Returns [ { action: "LOGIN", total: 42 }, … ] sorted by frequency.
     *
     * Optional: ?workspaceId= for SUPER_ADMIN cross-workspace, ?days=30 for time window.
     */
    @GetMapping("/users-activity")
    @PreAuthorize("hasAnyAuthority('REPORT_VIEW','REPORT_EXPORT')")
    public ResponseEntity<List<Map<String, Object>>> getUsersActivity(
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(defaultValue = "30") int days) {

        UUID wsId = resolveWorkspace(workspaceId);
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        List<Map<String, Object>> result = activityLogRepo.findActionFrequency(wsId, since)
                .stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("action", p.getAction());
                    m.put("total",  p.getTotal());
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ── inline DTOs ────────────────────────────────────────────────────────

    private UUID resolveWorkspace(UUID overrideWsId) {
        boolean isSuperAdmin = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        
        if (isSuperAdmin && "00000000-0000-0000-0000-000000000000".equals(String.valueOf(overrideWsId))) {
            return null; // Platform-level aggregation
        }
        if (isSuperAdmin && overrideWsId != null) {
            return overrideWsId;
        }
        return WorkspaceContext.currentWorkspaceId();
    }

    record ExportResponse(UUID exportId, String status) {}

    record ExportStatusResponse(
            UUID    exportId,
            String  status,
            String  format,
            Instant createdAt,
            Instant completedAt,
            String  filePath
    ) {}
}
