package et.com.cog.esms.core.messaging;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps an SMSC message id back to the message that produced it.
 *
 * A delivery receipt identifies itself only by the carrier's own id, so this
 * is the only way to attribute one to a message. A multi-part send registers
 * one row per segment, because the SMSC issues an id — and later a receipt —
 * for each segment individually.
 */
@Entity
@Table(name = "message_carrier_ref")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MessageCarrierRef {

    @Id
    @Column(name = "carrier_msg_id", nullable = false)
    private String carrierMsgId;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
