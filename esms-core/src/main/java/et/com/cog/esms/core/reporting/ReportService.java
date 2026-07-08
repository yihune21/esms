package et.com.cog.esms.core.reporting;

import et.com.cog.esms.core.messaging.Message;
import et.com.cog.esms.core.messaging.MessageRepository;
import et.com.cog.esms.core.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;


@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final MessageRepository messageRepo;
    private final ReportExportRepository exportRepo;
    private final ApplicationEventPublisher eventPublisher;

    public DeliveryReport getDeliveryReport(UUID workspaceId,
                                            Instant from,
                                            Instant to,
                                            UUID campaignId,
                                            String branch,
                                            String status,
                                            Pageable pageable) {

        // findFiltered is a NATIVE query with its own hardcoded "ORDER BY m.created_at DESC".
        // Spring Data does NOT translate Java property names (createdAt) to DB column
        // names (created_at) for native queries — it only does that for JPQL. If the
        // incoming Pageable carries a Sort (e.g. from a client's ?sort=createdAt,desc,
        // or a @PageableDefault upstream), Spring appends it to the query verbatim as
        // "m.createdAt", which Postgres rejects since the real column is "created_at".
        // We strip the Sort here and rely entirely on the query's own ORDER BY.
        Pageable unsortedPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        List<Message> messages = messageRepo.findFiltered(workspaceId, from, to, campaignId, status, branch, unsortedPage);
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

    public List<ReportExport> listExports(UUID workspaceId) {
        return exportRepo.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    public ReportExport getExport(UUID exportId) {
        return exportRepo.findById(exportId)
                .orElseThrow(() -> new IllegalArgumentException("Export not found: " + exportId));
    }

   
    public List<DailyTrendDto> getDailyTrend(UUID workspaceId, Instant from, Instant to) {
        return messageRepo.findDailyTrend(workspaceId, from, to)
                .stream()
                .map(p -> new DailyTrendDto(p.getDay(), p.getStatus(), p.getTotal()))
                .toList();
    }


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


    public record DailyTrendDto(String day, String status, long total) {}

    public record CampaignSummaryDto(
            UUID   campaignId,
            long   sent,
            long   delivered,
            long   failed,
            long   pending
    ) {}

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }
}