package et.com.cog.esms.core.template;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Reusable SMS message template with approval lifecycle.
 *
 * A template can optionally carry its own recipients in two ways:
 *   1. {@code recipientGroupId} — links to an existing contact group.
 *   2. Inline {@code TemplateRecipient} rows (managed separately via
 *      {@link TemplateRecipientRepository}) — fixed phone numbers
 *      embedded directly in the template.
 *
 * When launching a campaign from this template, esms-sender resolves
 * the final recipient list by merging both sources.
 *
 * Reference: LLD §4.4 – template table (V003, V012, V017)
 */
@Entity
@Table(name = "template")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String name;

    /** Optional human-readable description of what this template is for. */
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /** GSM7 or UCS2 */
    @Column(nullable = false)
    private String encoding;

    /** DRAFT, APPROVED, RETIRED, REJECTED */
    @Column(nullable = false)
    private String status;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private java.util.List<String> variables;

    private String sender;

    /**
     * Optional contact group whose members are used as recipients when a
     * campaign is created from this template. Can be combined with inline
     * {@link TemplateRecipient} rows — the sender merges both.
     */
    @Column(name = "recipient_group_id")
    private UUID recipientGroupId;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
