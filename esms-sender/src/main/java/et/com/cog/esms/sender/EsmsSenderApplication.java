package et.com.cog.esms.sender;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * eSMS-Sender — the dedicated SMS Sending Microservice.
 * Narrow responsibility: consume approved messages from RabbitMQ
 * and deliver them to the telecom gateways, then report delivery status back.
 * Contains no user-facing surface, no approval logic, no contact data.
 */
@SpringBootApplication
public class EsmsSenderApplication {

    public static void main(String[] args) {
        SpringApplication.run(EsmsSenderApplication.class, args);
    }
}
