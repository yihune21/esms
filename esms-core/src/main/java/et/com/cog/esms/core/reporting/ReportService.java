package et.com.cog.esms.core.reporting;

import et.com.cog.esms.core.messaging.Message;
import et.com.cog.esms.core.messaging.MessageRepository;
import et.com.cog.esms.core.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reporting service — aggregates message delivery statistics per workspace.
 * Reference: LLD §6.7
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final MessageRepository messageRepo;
    private final ReportExportRepository exportRepo;
    private final ApplicationEventPublisher eventPublisher;

    /** Summary totals + filtered rows for the delivery dashboard. */
    public DeliveryReport getDeliveryReport(UUID workspaceId,
                                            Instant from,
                                            Instant to,
                                            UUID campaignId,
                                            String branch,
                                            String status,
                                            Pageable pageable) {

        List<Message> messages = messageRepo.findFiltered(workspaceId, from, to, campaignId, status, branch, pageable);
        long total = messageRepo.countFiltered(workspaceId, from, to, campaignId, status, branch);

        long sent      = messageRepo.countByWorkspaceIdAndStatus(workspaceId, "SENT");
        long delivered = messageRepo.countByWorkspaceIdAndStatus(workspaceId, "DELIVERED");
        long failed    = messageRepo.countByWorkspaceIdAndStatus(workspaceId, "FAILED");
        long pending   = messageRepo.countByWorkspaceIdAndStatus(workspaceId, "PENDING")
                       + messageRepo.countByWorkspaceIdAndStatus(workspaceId, "QUEUED");

        List<DeliveryReport.MessageRow> rows = messages.stream()
                .map(m -> new DeliveryReport.MessageRow(
                        m.getId(),
                        maskPhone(m.getToNumber()),
                        m.getStatus(),
                        m.getCampaignId(),
                        m.getResolvedCarrier(),
                        m.getSentAt(),
                        m.getDeliveredAt(),
                        m.getCreatedAt()
                ))
                .toList();

        return new DeliveryReport(
                new DeliveryReport.Totals(sent, delivered, failed, pending),
                rows,
                total
        );
    }

    /** Enqueue an async export job and return its tracking record. */
    @Transactional
    public ReportExport requestExport(UUID workspaceId, ReportExportRequest req) {
        ReportExport export = ReportExport.builder()
                .workspaceId(workspaceId)
                .requestedBy(WorkspaceContext.currentUserId())
                .format(req.getFormat())
                .filterJson(req.toFilterJson())
                .status("RUNNING")
                .createdAt(Instant.now())
                .build();
        export = exportRepo.save(export);
        log.info("Export job created: id={}, format={}", export.getId(), export.getFormat());
        eventPublisher.publishEvent(new ReportExportEvent(export.getId(), req));
        return export;
    }

    /** List all export jobs for a workspace, most-recent first. */
    public List<ReportExport> listExports(UUID workspaceId) {
        return exportRepo.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /** Retrieve export job status (and stream file URL when DONE). */
    public ReportExport getExport(UUID exportId) {
        return exportRepo.findById(exportId)
                .orElseThrow(() -> new IllegalArgumentException("Export not found: " + exportId));
    }

    // ── Aggregation ──────────────────────────────────────────────────────────

    /**
     * Daily delivery trend for the workspace within a date range.
     * Each point represents a (day, status, count) tuple.
     */
    public List<DailyTrendDto> getDailyTrend(UUID workspaceId, Instant from, Instant to) {
        return messageRepo.findDailyTrend(workspaceId, from, to)
                .stream()
                .map(p -> new DailyTrendDto(p.getDay(), p.getStatus(), p.getTotal()))
                .toList();
    }

    /**
     * Per-campaign message summary for the workspace within a date range.
     * Columns: campaignId, sent, delivered, failed, pending.
     */
    public List<CampaignSummaryDto> getCampaignSummaries(UUID workspaceId, Instant from, Instant to) {
        return messageRepo.findCampaignSummaries(workspaceId, from, to)
                .stream()
                .map(p -> new CampaignSummaryDto(
                        p.getCampaignId(),
                        p.getSent(),
                        p.getDelivered(),
                        p.getFailed(),
                        p.getPending()))
                .toList();
    }

    // ── Transfer objects ─────────────────────────────────────────────────────

    public record DailyTrendDto(String day, String status, long total) {}

    public record CampaignSummaryDto(
            UUID   campaignId,
            long   sent,
            long   delivered,
            long   failed,
            long   pending
    ) {}

    /** Mask phone for privacy: keeps last 4 digits, e.g. ****6789 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }
}
