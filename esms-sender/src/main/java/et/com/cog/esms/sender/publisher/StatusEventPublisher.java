package et.com.cog.esms.sender.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.common.constants.QueueConstants;
import et.com.cog.esms.common.dto.StatusEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes StatusEvent messages to sms.dlr.q for eSMS-Core to consume.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatusEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publish(StatusEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(QueueConstants.EXCHANGE_SMS, "sms.dlr", payload);
            log.debug("Published StatusEvent: messageId={}, status={}",
                    event.getMessageId(), event.getStatus());
        } catch (Exception e) {
            log.error("Failed to publish StatusEvent for messageId={}: {}",
                    event.getMessageId(), e.getMessage());
        }
    }
}
