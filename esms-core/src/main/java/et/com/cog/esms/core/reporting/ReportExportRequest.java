package et.com.cog.esms.core.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Request body for POST /reports/exports.
 * Reference: LLD §6.7
 */
@Data
public class ReportExportRequest {

    /** "XLSX" or "CSV" */
    private String format = "XLSX";

    private UUID   campaignId;
    private String status;
    private Instant from;
    private Instant to;
    private String branch;

    /** Serialise filter fields to JSON for storage in the export record. */
    public String toFilterJson() {
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }
}
