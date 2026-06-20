package et.com.cog.esms.core.reminder;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a reminder rule tied to an Excel upload of insurance/policy holders.
 *
 * How it works:
 *  - A user uploads an Excel file containing policy holders and their insurance expiry dates.
 *  - The system checks daily: for every contact in the upload whose expiry date is
 *    exactly {@code triggerDays} days away from today, an SMS reminder is sent.
 *  - This is NOT a scheduled SMS — it fires immediately when the trigger condition is met.
 *
 * Statuses: ACTIVE | INACTIVE
 *
 * Maps to the {@code schedule} table (renamed semantically to "reminder").
 * Reference: LLD §4.5
 */
@Entity
@Table(name = "schedule")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** Human-readable name for this reminder rule. */
    @Column(nullable = false)
    private String name;

    /**
     * The contact group linked to the Excel upload.
     * Can be used as an alternative to {@code uploadId}.
     */
    @Column(name = "recipient_group_id")
    private UUID recipientGroupId;

    /**
     * ID of the Excel upload containing policy holders with their expiry dates.
     * The system reads this upload daily to find contacts matching the trigger condition.
     */
    @Column(name = "upload_id")
    private UUID uploadId;

    /** Optional custom SMS body. If null, the linked template body is used. */
    @Column(name = "custom_body")
    private String customBody;

    /**
     * Number of days before the insurance expiry date to trigger the reminder.
     * For example: 15 means "send when exactly 15 days remain before expiry".
     * Stored in the {@code trigger_days} column.
     */
    @Column(name = "trigger_days", nullable = false)
    private Integer triggerDays;

    /**
     * Kind of reminder: T_MINUS_30 | T_MINUS_10 | CUSTOM
     * CUSTOM means {@code triggerDays} is set manually.
     */
    @Column(nullable = false)
    private String kind;

    /** Template to use for the reminder SMS. */
    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    /**
     * ACTIVE — the daily poller will evaluate and send for matching contacts.
     * INACTIVE — the rule is paused and no messages will be sent.
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
