package et.com.cog.esms.core.reporting;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;


@Data
@AllArgsConstructor
public class DeliveryReport {

    private Totals totals;
    private List<MessageRow> rows;
    private long totalCount;

    @Data
    @AllArgsConstructor
    public static class Totals {
        private long sent;
        private long delivered;
        private long failed;
        private long pending;
    }

    @Data
    @AllArgsConstructor
    public static class MessageRow {
        private UUID   id;
        private String to;          // masked: ****6789
        private String status;
        private UUID   campaignId;
        private String carrier;
        private Instant sentAt;
        private Instant deliveredAt;
        private Instant createdAt;
    }
}
