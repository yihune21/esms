package et.com.cog.esms.common.constants;

/**
 * RabbitMQ topology constants — shared between Core (publisher) and Sender (consumer).
 
 */
public final class QueueConstants {

    private QueueConstants() {}

    // Exchanges
    public static final String EXCHANGE_SMS       = "sms.exchange";
    public static final String EXCHANGE_DELAYED   = "sms.delayed";
    public static final String EXCHANGE_DLX       = "sms.dlx";

    // Queues
    public static final String QUEUE_SEND         = "sms.send.q";
    public static final String QUEUE_RETRY        = "sms.retry.q";
    public static final String QUEUE_DLQ          = "sms.send.dlq";
    public static final String QUEUE_DLR          = "sms.dlr.q";
    public static final String QUEUE_INBOUND      = "sms.in.q";

    // Routing keys
    public static final String RK_SEND            = "sms.send.#";
    public static final String RK_DLR             = "sms.dlr.#";
    public static final String RK_RETRY           = "sms.retry";
    public static final String RK_DLQ             = "sms.send.dlq";
}
