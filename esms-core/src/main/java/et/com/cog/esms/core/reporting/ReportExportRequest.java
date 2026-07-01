package et.com.cog.esms.core.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;


@Data
public class ReportExportRequest {

    private String format = "XLSX";

    private UUID   campaignId;
    private String status;
    private Instant from;
    private Instant to;
    private String branch;

    public String toFilterJson() {
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }
}
