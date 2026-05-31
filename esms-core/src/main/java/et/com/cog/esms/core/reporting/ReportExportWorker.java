package et.com.cog.esms.core.reporting;

import et.com.cog.esms.core.messaging.Message;
import et.com.cog.esms.core.messaging.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportExportWorker {

    private final MessageRepository messageRepo;
    private final ReportExportRepository exportRepo;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void handleExportEvent(ReportExportEvent event) {
        UUID exportId = event.exportId();
        ReportExportRequest req = event.request();
        log.info("Starting async export generation for job ID: {}", exportId);

        ReportExport export = exportRepo.findById(exportId).orElse(null);
        if (export == null) {
            log.error("Export record not found for ID: {}", exportId);
            return;
        }

        try {
            // Fetch messages matching filters (max 100,000 to prevent OOM)
            List<Message> messages = messageRepo.findFiltered(
                    export.getWorkspaceId(),
                    req.getFrom(),
                    req.getTo(),
                    req.getCampaignId(),
                    req.getStatus(),
                    req.getBranch(),
                    PageRequest.of(0, 100000)
            );

            log.info("Fetched {} messages for export job {}", messages.size(), exportId);

            // Ensure exports directory exists
            File dir = new File("exports");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String format = export.getFormat().toUpperCase();
            String filename = "export-" + exportId + "." + format.toLowerCase();
            File file = new File(dir, filename);

            if ("XLSX".equals(format)) {
                writeExcel(file, messages);
            } else {
                writeCsv(file, messages);
            }

            export.setStatus("DONE");
            export.setFilePath(file.getPath());
            export.setCompletedAt(Instant.now());
            exportRepo.save(export);
            log.info("Export job {} completed successfully. Saved to {}", exportId, file.getPath());

        } catch (Exception e) {
            log.error("Failed to generate export for job ID: {}", exportId, e);
            export.setStatus("FAILED");
            export.setCompletedAt(Instant.now());
            exportRepo.save(export);
        }
    }

    private void writeExcel(File file, List<Message> messages) throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(file)) {
            Sheet sheet = workbook.createSheet("Messages");

            // Header row
            Row header = sheet.createRow(0);
            String[] columns = {"Message ID", "To Number", "Status", "Campaign ID", "Carrier", "Sent At", "Delivered At", "Created At"};
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }

            // Data rows
            int rowNum = 1;
            for (Message m : messages) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(m.getId().toString());
                row.createCell(1).setCellValue(m.getToNumber());
                row.createCell(2).setCellValue(m.getStatus());
                row.createCell(3).setCellValue(m.getCampaignId() != null ? m.getCampaignId().toString() : "");
                row.createCell(4).setCellValue(m.getResolvedCarrier() != null ? m.getResolvedCarrier() : "");
                row.createCell(5).setCellValue(m.getSentAt() != null ? m.getSentAt().toString() : "");
                row.createCell(6).setCellValue(m.getDeliveredAt() != null ? m.getDeliveredAt().toString() : "");
                row.createCell(7).setCellValue(m.getCreatedAt() != null ? m.getCreatedAt().toString() : "");
            }

            // Auto-size all columns for readability
            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
        }
    }

    private void writeCsv(File file, List<Message> messages) throws Exception {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("Message ID,To Number,Status,Campaign ID,Carrier,Sent At,Delivered At,Created At");
            for (Message m : messages) {
                writer.println(String.format("%s,%s,%s,%s,%s,%s,%s,%s",
                        m.getId(),
                        escapeCsv(m.getToNumber()),
                        escapeCsv(m.getStatus()),
                        m.getCampaignId() != null ? m.getCampaignId().toString() : "",
                        escapeCsv(m.getResolvedCarrier()),
                        m.getSentAt() != null ? m.getSentAt().toString() : "",
                        m.getDeliveredAt() != null ? m.getDeliveredAt().toString() : "",
                        m.getCreatedAt() != null ? m.getCreatedAt().toString() : ""
                ));
            }
        }
    }

    private String escapeCsv(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n") || val.contains("\r")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
