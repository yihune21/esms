package et.com.cog.esms.core.reporting;

import java.util.UUID;


public record ReportExportEvent(UUID exportId, ReportExportRequest request) {
}
