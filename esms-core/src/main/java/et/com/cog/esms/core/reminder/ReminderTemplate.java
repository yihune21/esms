package et.com.cog.esms.core.reminder;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A reusable reminder definition: name, message, and days-left rule. Users
 * create/edit/activate/deactivate these freely — no approval. Actually sending
 * one (with uploaded policy data) creates a separate, approval-gated Reminder
 * run (the `schedule` table) that references this template.
 */
@Entity
@Table(name = "reminder_template")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ReminderTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String name;

    @Column(name = "custom_body")
    private String customBody;

    // Optional reference to an approved message Template (templates table).
    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "trigger_days", nullable = false)
    private Integer triggerDays;

    @Column(nullable = false)
    private String kind;

    // ACTIVE | INACTIVE — a deactivated template can't be used to send.
    @Column(nullable = false)
    private String status;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
