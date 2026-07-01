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
import com.rabbitmq.client.Channel;
import org.springframework.stereotype.Component;

import java.time.Instant;


@Slf4j
@Component
@RequiredArgsConstructor
public class SendCommandConsumer {

    private final SmsGateway gateway;
    private final StatusEventPublisher statusPublisher;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QueueConstants.QUEUE_SEND, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            String body = new String(message.getBody());
            SendCommand cmd = objectMapper.readValue(body, SendCommand.class);

            log.info("Received SendCommand: messageId={}, to={}, carrier={}",
                    cmd.getMessageId(), cmd.getTo(), cmd.getResolvedCarrier());

            statusPublisher.publish(StatusEvent.builder()
                    .messageId(cmd.getMessageId())
                    .workspaceId(cmd.getWorkspaceId())
                    .status(MessageStatus.QUEUED)
                    .carrier(cmd.getResolvedCarrier())
                    .timestamp(Instant.now())
                    .build());

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
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
