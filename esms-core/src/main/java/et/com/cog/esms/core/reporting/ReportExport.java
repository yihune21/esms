package et.com.cog.esms.core.reporting;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "report_export")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ReportExport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(nullable = false, length = 10)
    private String format;

    @Column(name = "filter_json", columnDefinition = "TEXT")
    private String filterJson;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
