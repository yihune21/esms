package et.com.cog.esms.sender.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.common.constants.QueueConstants;
import et.com.cog.esms.common.dto.SendCommand;
import et.com.cog.esms.common.dto.StatusEvent;
import et.com.cog.esms.common.enums.MessageStatus;
import et.com.cog.esms.sender.config.RetrySchedule;
import et.com.cog.esms.sender.gateway.SmsGateway;
import et.com.cog.esms.sender.publisher.StatusEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.rabbitmq.client.Channel;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SendCommandConsumer {

    /** Attempt counter carried on the AMQP message across retry hops. */
    private static final String ATTEMPT_HEADER = "x-esms-attempt";

    /**
     * SMSC rejections that are worth another attempt. Everything else is the
     * SMSC telling us this message will never be accepted as-is (bad
     * destination, bad source address, invalid parameter), so retrying just
     * burns the schedule and delays the FAILED status the operator needs.
     *
     *   0x14 ESME_RMSGQFUL   — SMSC message queue full
     *   0x58 ESME_RTHROTTLED — we are sending too fast
     */
    private static final Set<String> RETRYABLE_NACKS = Set.of("NACK_14", "NACK_58");

    /**
     * Gateway-level faults that are inherently transient — the link was down or
     * the SMSC did not answer in time. Retrying is exactly right here, and
     * dropping the message (the previous behaviour) was exactly wrong.
     */
    private static final Set<String> RETRYABLE_ERRORS =
            Set.of("SESSION_NOT_BOUND", "RESPONSE_TIMEOUT", "IO_ERROR", "INVALID_RESPONSE");

    private final SmsGateway gateway;
    private final StatusEventPublisher statusPublisher;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RetrySchedule retrySchedule;

    @RabbitListener(queues = QueueConstants.QUEUE_SEND, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            String body = new String(message.getBody());
            SendCommand cmd = objectMapper.readValue(body, SendCommand.class);
            int attempt = currentAttempt(message);

            log.info("Received SendCommand: messageId={}, to={}, carrier={}, attempt={}/{}",
                    cmd.getMessageId(), cmd.getTo(), cmd.getResolvedCarrier(),
                    attempt, retrySchedule.getMaxAttempts());

            // Only announce QUEUED on the first attempt — a retry would
            // otherwise drag the message backwards out of its real status.
            if (attempt == 1) {
                statusPublisher.publish(StatusEvent.builder()
                        .messageId(cmd.getMessageId())
                        .workspaceId(cmd.getWorkspaceId())
                        .status(MessageStatus.QUEUED)
                        .carrier(cmd.getResolvedCarrier())
                        .timestamp(Instant.now())
                        .build());
            }

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
                        // Every segment's SMSC id, so the core can register a
                        // lookup row per segment and match the receipts that
                        // come back later quoting only the SMSC's own id.
                        .carrierMsgIds(result.carrierMsgIds())
                        .timestamp(Instant.now())
                        .build());
                channel.basicAck(deliveryTag, false);
                return;
            }

            if (isRetryable(result.errorCode()) && retrySchedule.hasAttemptsLeft(attempt)) {
                scheduleRetry(message, attempt, cmd, result.errorCode());
                channel.basicAck(deliveryTag, false);
                return;
            }

            log.error("Send failed permanently: messageId={}, attempt={}, errorCode={}, detail={}",
                    cmd.getMessageId(), attempt, result.errorCode(), result.errorMessage());
            statusPublisher.publish(StatusEvent.builder()
                    .messageId(cmd.getMessageId())
                    .workspaceId(cmd.getWorkspaceId())
                    .status(MessageStatus.FAILED)
                    .carrier(cmd.getResolvedCarrier())
                    .errorCode(result.errorCode())
                    .timestamp(Instant.now())
                    .build());
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            // A message we cannot even parse is poison — reject it to the DLQ
            // rather than retrying it forever.
            log.error("Error processing SendCommand: {}", e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * Republishes onto the holding queue for this attempt. The queue's TTL
     * expires and dead-letters it back onto sms.send.q, which is what actually
     * produces the backoff.
     */
    private void scheduleRetry(Message original, int attempt, SendCommand cmd, String errorCode) {
        String retryQueue = retrySchedule.queueForAttempt(attempt);

        MessageProperties props = new MessageProperties();
        props.setContentType(original.getMessageProperties().getContentType());
        props.setHeader(ATTEMPT_HEADER, attempt + 1);
        props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);

        // Default exchange + routing key = queue name delivers straight to it.
        rabbitTemplate.send("", retryQueue, new Message(original.getBody(), props));

        log.warn("Send failed transiently (errorCode={}) — messageId={} re-queued on {} for attempt {}/{}",
                errorCode, cmd.getMessageId(), retryQueue, attempt + 1, retrySchedule.getMaxAttempts());
    }

    private boolean isRetryable(String errorCode) {
        if (errorCode == null) return false;
        if (RETRYABLE_ERRORS.contains(errorCode)) return true;
        return errorCode.startsWith("NACK_") && RETRYABLE_NACKS.contains(errorCode);
    }

    private int currentAttempt(Message message) {
        Object raw = message.getMessageProperties().getHeader(ATTEMPT_HEADER);
        if (raw instanceof Number n) return Math.max(1, n.intValue());
        return 1;
    }
}
