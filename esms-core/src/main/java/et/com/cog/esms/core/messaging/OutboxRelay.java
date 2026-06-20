package et.com.cog.esms.core.messaging;

// import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.common.constants.QueueConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Outbox Relay — polls unpublished outbox events and publishes to RabbitMQ.
 * Uses publisher confirms for at-least-once delivery.
 * Crash-safe: if the process dies after DB commit but before publish,
 * the next poll picks up the unpublished rows.
 * Reference: LLD §10.1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final RabbitTemplate rabbitTemplate;
    private final et.com.cog.esms.core.campaign.CampaignDispatchService campaignDispatchService;

    /**
     * Poll every 2 seconds for unpublished outbox events.
     */
    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void relay() {
        List<OutboxEvent> events = outboxRepo.findUnpublished();
        if (events.isEmpty()) return;

        log.debug("Relaying {} outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                if ("ScheduledFire".equals(event.getEventType()) || "ReminderFire".equals(event.getEventType())) {
                    campaignDispatchService.dispatch(event);
                } else {
                    rabbitTemplate.convertAndSend(
                            QueueConstants.EXCHANGE_SMS,
                            "sms.send",
                            event.getPayload()
                    );
                }
                event.setPublishedAt(Instant.now());
                outboxRepo.save(event);
            } catch (Exception e) {
                log.error("Failed to publish/process outbox event {}: {}", event.getId(), e.getMessage());
                // Will be retried on next poll
                break;
            }
        }
    }
}
