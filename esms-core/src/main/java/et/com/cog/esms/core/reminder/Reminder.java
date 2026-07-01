package et.com.cog.esms.core.reminder;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;


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

    @Column(nullable = false)
    private String name;

  
    @Column(name = "recipient_group_id")
    private UUID recipientGroupId;

    @Column(name = "upload_id")
    private UUID uploadId;

    @Column(name = "custom_body")
    private String customBody;


    @Column(name = "trigger_days", nullable = false)
    private Integer triggerDays;

 
    @Column(nullable = false)
    private String kind;

    @Column(name = "template_id")
    private UUID templateId;


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
