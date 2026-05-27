package et.com.cog.esms.core.reporting;

import java.util.UUID;

/**
 * Event published when a new report export is requested.
 */
public record ReportExportEvent(UUID exportId, ReportExportRequest request) {
}
