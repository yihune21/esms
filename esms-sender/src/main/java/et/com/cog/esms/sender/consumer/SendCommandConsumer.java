package et.com.cog.esms.sender.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.common.constants.QueueConstants;
import et.com.cog.esms.common.dto.SendCommand;
import et.com.cog.esms.common.dto.StatusEvent;
import et.com.cog.esms.common.enums.MessageStatus;
import et.com.cog.esms.sender.gateway.SmsGateway;
import et.com.cog.esms.sender.publisher.StatusEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.rabbitmq.client.Channel;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Consumes SendCommand messages from sms.send.q and dispatches via the appropriate gateway.
 * Emits StatusEvents to sms.dlr.q for Core to consume.
 * Reference: HLD §8.4, LLD §10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendCommandConsumer {

    private final List<SmsGateway> gateways;
    private final StatusEventPublisher statusPublisher;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = QueueConstants.QUEUE_SEND, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            String body = new String(message.getBody());
            SendCommand cmd = objectMapper.readValue(body, SendCommand.class);

            log.info("Received SendCommand: messageId={}, to={}, carrier={}",
                    cmd.getMessageId(), cmd.getTo(), cmd.getResolvedCarrier());

            // Emit QUEUED status
            statusPublisher.publish(StatusEvent.builder()
                    .messageId(cmd.getMessageId())
                    .workspaceId(cmd.getWorkspaceId())
                    .status(MessageStatus.QUEUED)
                    .carrier(cmd.getResolvedCarrier())
                    .timestamp(Instant.now())
                    .build());

            // Find the appropriate gateway
            SmsGateway gateway = resolveGateway(cmd.getResolvedCarrier());

            // Send
            SmsGateway.SendResult result = gateway.sendSms(new SmsGateway.SendRequest(
                    cmd.getMessageId().toString(),
                    cmd.getTo(),
                    cmd.getBody(),
                    cmd.getSenderMask(),
                    cmd.getEncoding() != null ? cmd.getEncoding().name() : "GSM7",
                    cmd.getCallbackUrl()
            ));

            if (result.success()) {
                statusPublisher.publish(StatusEvent.builder()
                        .messageId(cmd.getMessageId())
                        .workspaceId(cmd.getWorkspaceId())
                        .status(MessageStatus.SENT)
                        .carrier(cmd.getResolvedCarrier())
                        .carrierMsgId(result.carrierMsgId())
                        .timestamp(Instant.now())
                        .build());
            } else {
                statusPublisher.publish(StatusEvent.builder()
                        .messageId(cmd.getMessageId())
                        .workspaceId(cmd.getWorkspaceId())
                        .status(MessageStatus.FAILED)
                        .carrier(cmd.getResolvedCarrier())
                        .errorCode(result.errorCode())
                        .timestamp(Instant.now())
                        .build());
            }

            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("Error processing SendCommand: {}", e.getMessage(), e);
            // Reject and requeue (will go to DLQ after max retries)
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private SmsGateway resolveGateway(String carrier) {
        return gateways.stream()
                .filter(g -> g.carrier().name().equalsIgnoreCase(carrier)
                        || "dummy".equalsIgnoreCase(carrier))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("No gateway found for carrier '{}', using first available", carrier);
                    return gateways.get(0);
                });
    }
}
