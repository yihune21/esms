package et.com.cog.esms.sender.config;

import et.com.cog.esms.common.constants.QueueConstants;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;


@Configuration
public class SenderRabbitConfig {

    @Bean
    public TopicExchange smsExchange() {
        return new TopicExchange(QueueConstants.EXCHANGE_SMS, true, false);
    }

    @Bean
    public DirectExchange smsDlxExchange() {
        return new DirectExchange(QueueConstants.EXCHANGE_DLX, true, false);
    }

    @Bean
    public Queue smsSendQueue() {
        return QueueBuilder.durable(QueueConstants.QUEUE_SEND)
                .withArgument("x-dead-letter-exchange", QueueConstants.EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", QueueConstants.RK_DLQ)
                .build();
    }

    @Bean
    public Queue smsDlrQueue() {
        return QueueBuilder.durable(QueueConstants.QUEUE_DLR).build();
    }

    @Bean
    public Queue smsDlqQueue() {
        return QueueBuilder.durable(QueueConstants.QUEUE_DLQ).build();
    }

    /**
     * One holding queue per backoff step. A queue here has no consumer — a
     * message sits for the queue's TTL and is then dead-lettered straight back
     * onto sms.send.q for another attempt. This is what makes
     * app.sender.retry.backoff-steps actually do something.
     */
    @Bean
    public Declarables retryQueues(RetrySchedule schedule) {
        List<Declarable> declarables = new ArrayList<>();
        for (int i = 0; i < schedule.getQueueNames().size(); i++) {
            declarables.add(QueueBuilder.durable(schedule.getQueueNames().get(i))
                    .withArgument("x-message-ttl", schedule.getBackoffMillis().get(i))
                    .withArgument("x-dead-letter-exchange", QueueConstants.EXCHANGE_SMS)
                    .withArgument("x-dead-letter-routing-key", "sms.send")
                    .build());
        }
        return new Declarables(declarables);
    }

    @Bean
    public Binding sendBinding() {
        return BindingBuilder.bind(smsSendQueue()).to(smsExchange()).with("sms.send");
    }

    @Bean
    public Binding dlrBinding() {
        return BindingBuilder.bind(smsDlrQueue()).to(smsExchange()).with("sms.dlr");
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(smsDlqQueue()).to(smsDlxExchange()).with(QueueConstants.RK_DLQ);
    }
}
