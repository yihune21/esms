package et.com.cog.esms.core.messaging;

import et.com.cog.esms.common.constants.QueueConstants;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RabbitMqConfig {


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

    // Retry queues are owned by esms-sender (see SenderRabbitConfig.retryQueues),
    // which declares one per backoff step with the x-message-ttl that actually
    // produces the delay. The single sms.retry.q formerly declared here had a
    // dead-letter route back to sms.send but NO TTL, so anything published to
    // it would have looped instantly — nothing ever published to it, and the
    // retry path lived only in config until it was implemented sender-side.


    @Bean
    public Binding sendBinding() {
        return BindingBuilder.bind(smsSendQueue())
                .to(smsExchange())
                .with("sms.send");
    }

    @Bean
    public Binding dlrBinding() {
        return BindingBuilder.bind(smsDlrQueue())
                .to(smsExchange())
                .with("sms.dlr");
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(smsDlqQueue())
                .to(smsDlxExchange())
                .with(QueueConstants.RK_DLQ);
    }
}
