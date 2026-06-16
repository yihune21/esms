package et.com.cog.esms.core.contact;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks a batch Excel/CSV upload of contacts.
 * The upload lifecycle: DRAFT → MAPPED → COMMITTED or FAILED.
 * Reference: LLD §4.3 – contact_upload table (V002)
 */
@Entity
@Table(name = "contact_upload")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ContactUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    /** DRAFT, MAPPED, COMMITTED, FAILED */
    @Column(nullable = false)
    private String status;

    /** Detected column headers from the uploaded file */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detected_cols", columnDefinition = "jsonb")
    private List<String> detectedCols;

    /** Column mapping: Excel column → contact field */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> mapping;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "imported_count")
    private Integer importedCount;

    @Column(name = "duplicate_count")
    private Integer duplicateCount;

    @Column(name = "error_count")
    private Integer errorCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> errors;

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
