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
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatusEventConsumer {

    private final MessageRepository messageRepo;
    private final MessageStatusEventRepository statusEventRepo;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QueueConstants.QUEUE_DLR, ackMode = "MANUAL")
    @Transactional
    public void onStatusEvent(Message amqpMessage, Channel channel) throws Exception {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        try {
            String body = new String(amqpMessage.getBody());
            StatusEvent event = objectMapper.readValue(body, StatusEvent.class);

            log.info("DLR received: messageId={}, status={}, carrier={}",
                    event.getMessageId(), event.getStatus(), event.getCarrier());

            messageRepo.findById(event.getMessageId()).ifPresentOrElse(
                    msg -> {
                        UUID wsId = event.getWorkspaceId() != null ? event.getWorkspaceId() : msg.getWorkspaceId();
                        MessageStatusEvent statusRecord = MessageStatusEvent.builder()
                                .messageId(event.getMessageId())
                                .workspaceId(wsId)
                                .status(event.getStatus().name())
                                .carrier(event.getCarrier())
                                .carrierMsgId(event.getCarrierMsgId())
                                .errorCode(event.getErrorCode())
                                .createdAt(event.getTimestamp() != null ? event.getTimestamp() : Instant.now())
                                .build();
                        statusEventRepo.save(statusRecord);

                        msg.setStatus(event.getStatus().name());
                        if (event.getCarrierMsgId() != null) msg.setCarrierMsgId(event.getCarrierMsgId());
                        if (event.getErrorCode() != null)   msg.setErrorCode(event.getErrorCode());

                        switch (event.getStatus()) {
                            case SENT      -> msg.setSentAt(Instant.now());
                            case DELIVERED -> msg.setDeliveredAt(Instant.now());
                            default        -> {}
                        }
                        messageRepo.save(msg);
                        log.debug("Message {} status → {}", msg.getId(), event.getStatus());
                    },
                    () -> log.warn("StatusEvent for unknown messageId: {}", event.getMessageId())
            );

            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("Failed to process StatusEvent: {}", e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
