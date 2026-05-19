package et.com.cog.esms.sender.gateway;

import et.com.cog.esms.common.dto.StatusEvent;
import et.com.cog.esms.common.enums.Carrier;

import java.util.List;
import java.util.Map;

/**
 * SMS Gateway adapter contract.
 * Each carrier implements this interface.
 * Reference: ISD §10, LLD §11
 */
public interface SmsGateway {

    /**
     * Send a single SMS. Returns the result (success/failure + carrier message ID).
     */
    SendResult sendSms(SendRequest request);

    /**
     * Send a batch of SMS messages.
     */
    List<SendResult> sendBulk(List<SendRequest> requests);

    /**
     * Which carrier this adapter serves.
     */
    Carrier carrier();

    /**
     * Translate a carrier-specific DLR webhook payload into our canonical StatusEvent.
     */
    StatusEvent translateDlr(Map<String, Object> carrierPayload);

    /**
     * Health check — can we reach the carrier gateway?
     */
    HealthStatus health();

    record SendRequest(
            String messageId,
            String to,
            String body,
            String senderId,
            String encoding,
            String callbackUrl
    ) {}

    record SendResult(
            boolean success,
            String carrierMsgId,
            String errorCode,
            String errorMessage
    ) {}

    enum HealthStatus {
        HEALTHY, DEGRADED, UNAVAILABLE
    }
}
