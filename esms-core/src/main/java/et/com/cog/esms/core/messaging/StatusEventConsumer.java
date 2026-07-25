package et.com.cog.esms.core.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.common.constants.QueueConstants;
import et.com.cog.esms.common.dto.StatusEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import com.rabbitmq.client.Channel;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatusEventConsumer {

    private final MessageRepository messageRepo;
    private final MessageStatusEventRepository statusEventRepo;
    private final MessageCarrierRefRepository carrierRefRepo;
    private final ObjectMapper objectMapper;

    /**
     * Lifecycle ordering. Rabbit gives no cross-message ordering guarantee and
     * a multi-part send produces one receipt per segment, so events routinely
     * arrive out of order: a late ENROUTE ("SENT") after a DELIVERED would
     * otherwise drag the message backwards. A status is only applied if it
     * ranks at or above the current one — and FAILED/EXPIRED outrank
     * DELIVERED, because if any segment of a message failed, the recipient did
     * not receive the whole message.
     */
    private static int rank(String status) {
        return switch (status) {
            case "PENDING"   -> 0;
            case "QUEUED"    -> 1;
            case "SENT"      -> 2;
            case "DELIVERED" -> 3;
            case "FAILED", "EXPIRED" -> 4;
            default -> -1;
        };
    }

    @RabbitListener(queues = QueueConstants.QUEUE_DLR, ackMode = "MANUAL")
    @Transactional
    public void onStatusEvent(Message amqpMessage, Channel channel) throws Exception {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        try {
            String body = new String(amqpMessage.getBody());
            StatusEvent event = objectMapper.readValue(body, StatusEvent.class);

            log.info("Status event received: messageId={}, carrierMsgId={}, status={}, carrier={}",
                    event.getMessageId(), event.getCarrierMsgId(), event.getStatus(), event.getCarrier());

            et.com.cog.esms.core.messaging.Message msg = resolveMessage(event);
            if (msg == null) {
                // Nothing to attribute this to — ack so it doesn't spin. An SMSC
                // will occasionally emit receipts for ids we never issued.
                log.warn("Unattributable status event: messageId={}, carrierMsgId={} — dropping",
                        event.getMessageId(), event.getCarrierMsgId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            registerCarrierIds(event, msg.getId());

            UUID wsId = event.getWorkspaceId() != null ? event.getWorkspaceId() : msg.getWorkspaceId();
            statusEventRepo.save(MessageStatusEvent.builder()
                    .messageId(msg.getId())
                    .workspaceId(wsId)
                    .status(event.getStatus().name())
                    .carrier(event.getCarrier())
                    .carrierMsgId(event.getCarrierMsgId())
                    .errorCode(event.getErrorCode())
                    .createdAt(event.getTimestamp() != null ? event.getTimestamp() : Instant.now())
                    .build());

            String incoming = event.getStatus().name();
            if (rank(incoming) < rank(msg.getStatus())) {
                log.debug("Ignoring out-of-order status {} for message {} (already {})",
                        incoming, msg.getId(), msg.getStatus());
                channel.basicAck(deliveryTag, false);
                return;
            }

            msg.setStatus(incoming);
            if (event.getCarrierMsgId() != null && msg.getCarrierMsgId() == null) {
                msg.setCarrierMsgId(event.getCarrierMsgId());
            }
            if (event.getErrorCode() != null) msg.setErrorCode(event.getErrorCode());

            switch (event.getStatus()) {
                case SENT      -> { if (msg.getSentAt() == null) msg.setSentAt(Instant.now()); }
                case DELIVERED -> msg.setDeliveredAt(Instant.now());
                default        -> {}
            }
            messageRepo.save(msg);
            log.debug("Message {} status → {}", msg.getId(), incoming);

            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("Failed to process StatusEvent: {}", e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * Events published by the sender's consumer carry our own message id.
     * Delivery receipts come straight off the SMPP link and carry only the
     * SMSC's id, so those resolve through the refs recorded at submit time.
     */
    private et.com.cog.esms.core.messaging.Message resolveMessage(StatusEvent event) {
        if (event.getMessageId() != null) {
            return messageRepo.findById(event.getMessageId()).orElse(null);
        }
        String carrierId = event.getCarrierMsgId();
        if (carrierId == null) return null;

        return carrierRefRepo.findById(carrierId)
                .flatMap(ref -> messageRepo.findById(ref.getMessageId()))
                .or(() -> messageRepo.findFirstByCarrierMsgId(carrierId))
                .orElse(null);
    }

    /**
     * Records the lookup rows a later receipt will need. Carried on the SENT
     * event, one id per segment.
     */
    private void registerCarrierIds(StatusEvent event, UUID messageId) {
        List<String> ids = event.getCarrierMsgIds();
        if (ids == null || ids.isEmpty()) {
            ids = event.getCarrierMsgId() != null ? List.of(event.getCarrierMsgId()) : List.of();
        }
        for (String carrierId : ids) {
            if (carrierId == null || carrierId.isBlank()) continue;
            if (carrierRefRepo.existsById(carrierId)) continue;
            carrierRefRepo.save(MessageCarrierRef.builder()
                    .carrierMsgId(carrierId)
                    .messageId(messageId)
                    .build());
        }
    }
}
