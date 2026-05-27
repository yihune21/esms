package et.com.cog.esms.core.reporting;

import et.com.cog.esms.core.security.WorkspaceContext;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private final ReportService reportService;

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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID wsId = WorkspaceContext.currentWorkspaceId();
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

    // ── inline DTOs ────────────────────────────────────────────────────────

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
