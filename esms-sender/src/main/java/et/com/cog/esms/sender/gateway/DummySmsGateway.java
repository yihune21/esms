package et.com.cog.esms.sender.gateway;

import et.com.cog.esms.common.dto.StatusEvent;
import et.com.cog.esms.common.enums.Carrier;
import et.com.cog.esms.common.enums.MessageStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * Dummy SMS Gateway — writes to file, generates synthetic DLRs.
 * Used for dev/CI when no carrier sandbox is available.
 * Reference: ISD §14
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.sender.active-gateways", havingValue = "dummy", matchIfMissing = true)
public class DummySmsGateway implements SmsGateway {

    private static final Path OUTPUT_DIR = Path.of("./dummy-sms-output");

    @Override
    public SendResult sendSms(SendRequest request) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            String line = String.format("[%s] TO=%s | BODY=%s | SENDER=%s%n",
                    Instant.now(), request.to(), request.body(), request.senderId());
            Files.writeString(OUTPUT_DIR.resolve("sent.log"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            log.info("DUMMY SMS → {} (msgId={})", request.to(), request.messageId());

            String carrierMsgId = "DUMMY-" + UUID.randomUUID().toString().substring(0, 8);
            return new SendResult(true, carrierMsgId, null, null);

        } catch (IOException e) {
            log.error("Dummy gateway write failed: {}", e.getMessage());
            return new SendResult(false, null, "DUMMY_IO_ERROR", e.getMessage());
        }
    }

    @Override
    public List<SendResult> sendBulk(List<SendRequest> requests) {
        return requests.stream().map(this::sendSms).toList();
    }

    @Override
    public Carrier carrier() {
        return Carrier.ETHIO_TELECOM; // dummy acts as default carrier
    }

    @Override
    public StatusEvent translateDlr(Map<String, Object> carrierPayload) {
        // Dummy always reports DELIVERED
        return StatusEvent.builder()
                .messageId(UUID.fromString((String) carrierPayload.get("message_id")))
                .status(MessageStatus.DELIVERED)
                .carrier("dummy")
                .timestamp(Instant.now())
                .build();
    }

    @Override
    public HealthStatus health() {
        return HealthStatus.HEALTHY;
    }
}
