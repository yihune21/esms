package et.com.cog.esms.sender.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.common.dto.StatusEvent;
import et.com.cog.esms.common.enums.MessageStatus;
import et.com.cog.esms.sender.publisher.StatusEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook controller for inbound DLR callbacks from Ethio Telecom and Safaricom.
 * Reference: ISD §6.5, §7.4, §13
 */
@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class WebhookController {

    private final StatusEventPublisher statusPublisher;
    private final ObjectMapper objectMapper;

    @Value("${app.webhook.hmac-secret:dev-hmac-secret}")
    private String hmacSecret;

    @PostMapping({"/webhooks/ethiotelecom/dlr", "/sender/webhooks/ethiotelecom/dlr"})
    public ResponseEntity<Void> ethioDlr(
            @RequestBody String body,
            @RequestHeader(value = "X-EthioTelecom-Signature", required = false) String signature) {

        log.info("Received Ethio Telecom DLR webhook: body={}, signature={}", body, signature);

        // Verify signature if not in local dev mode or if signature is provided
        if (!"dev-hmac-secret".equals(hmacSecret) || signature != null) {
            if (!verifySignature(body, signature, hmacSecret)) {
                log.warn("Invalid Ethio Telecom DLR signature!");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {});
            processDlr(payload, "ETHIO_TELECOM");
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to parse Ethio Telecom DLR payload: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping({"/webhooks/safaricom/dlr", "/sender/webhooks/safaricom/dlr"})
    public ResponseEntity<Void> safaricomDlr(
            @RequestBody String body,
            @RequestHeader(value = "X-Safaricom-Signature", required = false) String signature) {

        log.info("Received Safaricom DLR webhook: body={}, signature={}", body, signature);

        // Verify signature if not in local dev mode or if signature is provided
        if (!"dev-hmac-secret".equals(hmacSecret) || signature != null) {
            if (!verifySignature(body, signature, hmacSecret)) {
                log.warn("Invalid Safaricom DLR signature!");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {});
            processDlr(payload, "SAFARICOM");
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to parse Safaricom DLR payload: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    private void processDlr(Map<String, Object> payload, String carrier) {
        String reference = (String) payload.get("reference");
        if (reference == null) {
            // fallback to message_id just in case
            reference = (String) payload.get("message_id");
        }

        if (reference == null) {
            throw new IllegalArgumentException("Missing reference/messageId in DLR payload");
        }

        UUID messageId = UUID.fromString(reference);
        String carrierMsgId = (String) payload.get("carrier_msg_id");
        String statusStr = (String) payload.get("status");
        String errorCode = (String) payload.get("error_code");
        String timestampStr = (String) payload.get("timestamp");

        MessageStatus status = MessageStatus.DELIVERED;
        if (statusStr != null) {
            try {
                status = MessageStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown DLR status '{}', defaulting to DELIVERED", statusStr);
            }
        }

        Instant timestamp = Instant.now();
        if (timestampStr != null) {
            try {
                timestamp = Instant.parse(timestampStr);
            } catch (Exception e) {
                log.warn("Failed to parse DLR timestamp '{}', using current time", timestampStr);
            }
        }

        StatusEvent event = StatusEvent.builder()
                .messageId(messageId)
                .status(status)
                .carrier(carrier)
                .carrierMsgId(carrierMsgId)
                .errorCode(errorCode)
                .timestamp(timestamp)
                .build();

        statusPublisher.publish(event);
        log.info("Processed DLR webhook and published StatusEvent: messageId={}, status={}, carrier={}",
                messageId, status, carrier);
    }

    private boolean verifySignature(String body, String signatureHeader, String secret) {
        if (signatureHeader == null || secret == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            
            String computedHex = bytesToHex(hash);
            String computedBase64 = Base64.getEncoder().encodeToString(hash);
            
            return MessageDigest.isEqual(computedHex.getBytes(StandardCharsets.UTF_8), signatureHeader.toLowerCase().getBytes(StandardCharsets.UTF_8))
                    || MessageDigest.isEqual(computedHex.toUpperCase().getBytes(StandardCharsets.UTF_8), signatureHeader.getBytes(StandardCharsets.UTF_8))
                    || MessageDigest.isEqual(computedBase64.getBytes(StandardCharsets.UTF_8), signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
