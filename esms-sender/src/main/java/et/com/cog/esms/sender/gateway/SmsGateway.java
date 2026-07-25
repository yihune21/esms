package et.com.cog.esms.sender.gateway;

import et.com.cog.esms.common.dto.StatusEvent;
import et.com.cog.esms.common.enums.Carrier;

import java.util.List;
import java.util.Map;

public interface SmsGateway {

    SendResult sendSms(SendRequest request);


    List<SendResult> sendBulk(List<SendRequest> requests);

    Carrier carrier();


    StatusEvent translateDlr(Map<String, Object> carrierPayload);


    HealthStatus health();

    record SendRequest(
            String messageId,
            String to,
            String body,
            String senderId,
            String encoding,
            String callbackUrl
    ) {}

    /**
     * @param carrierMsgId  the primary SMSC id (first segment) — what gets
     *                      stored on the message row
     * @param carrierMsgIds every segment's SMSC id, since the SMSC returns a
     *                      distinct id per submit_sm and sends a delivery
     *                      receipt against each one
     */
    record SendResult(
            boolean success,
            String carrierMsgId,
            List<String> carrierMsgIds,
            String errorCode,
            String errorMessage
    ) {
        /** Single-segment sends and failures: the primary id is the only id. */
        public SendResult(boolean success, String carrierMsgId,
                          String errorCode, String errorMessage) {
            this(success, carrierMsgId,
                 carrierMsgId != null ? List.of(carrierMsgId) : List.of(),
                 errorCode, errorMessage);
        }
    }

    enum HealthStatus {
        HEALTHY, DEGRADED, UNAVAILABLE
    }
}
