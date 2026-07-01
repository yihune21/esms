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
