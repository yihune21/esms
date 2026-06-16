package et.com.cog.esms.core.schedule;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a scheduled reminder tied to a policy record.
 * Kinds: T_MINUS_30, T_MINUS_10, CUSTOM
 * Statuses: PENDING, FIRED, CANCELLED
 *
 * Maps to the {@code schedule} table created in V003.
 * Reference: LLD §4.5
 */
@Entity
@Table(name = "schedule")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String name;

    @Column(name = "recipient_group_id")
    private UUID recipientGroupId;

    @Column(name = "upload_id")
    private UUID uploadId;

    @Column(name = "custom_body")
    private String customBody;

    /**
     * T_MINUS_30 | T_MINUS_10 | CUSTOM
     */
    @Column(nullable = false)
    private String kind;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    /**
     * ACTIVE | INACTIVE
     */
    @Column(nullable = false)
    private String status;

    @Column(name = "fired_at")
    private Instant firedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
