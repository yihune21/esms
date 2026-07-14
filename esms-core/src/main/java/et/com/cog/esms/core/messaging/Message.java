package et.com.cog.esms.core.messaging;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "message")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "reminder_id")
    private UUID reminderId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "to_number", nullable = false)
    private String toNumber;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private String encoding;

    @Column(name = "segment_count")
    private short segmentCount;

    @Column(name = "resolved_carrier")
    private String resolvedCarrier;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private String status;

    @Column(name = "carrier_msg_id")
    private String carrierMsgId;

    @Column(name = "error_code")
    private String errorCode;

    private short attempts;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
