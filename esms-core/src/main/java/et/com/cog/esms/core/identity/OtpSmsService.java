package et.com.cog.esms.core.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.common.dto.SendCommand;
import et.com.cog.esms.common.enums.Encoding;
import et.com.cog.esms.common.enums.Priority;
import et.com.cog.esms.common.sms.SmsSegments;
import et.com.cog.esms.core.admin.CarrierPrefix;
import et.com.cog.esms.core.admin.CarrierPrefixRepository;
import et.com.cog.esms.core.messaging.Message;
import et.com.cog.esms.core.messaging.MessageRepository;
import et.com.cog.esms.core.messaging.OutboxEvent;
import et.com.cog.esms.core.messaging.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Delivers the login OTP as an actual SMS, through the same outbox the
 * campaign pipeline uses. source='OTP' was already an allowed value in V004's
 * CHECK constraint and Priority.OTP already existed in the enum - the path was
 * designed and simply never built.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpSmsService {

    /**
     * What is stored on the message row. The live code is deliberately NOT
     * persisted: anyone holding REPORT_VIEW can list messages, so storing the
     * body verbatim would just move the OTP from the log file (the problem we
     * are fixing) into the reporting tables.
     */
    private static final String REDACTED_BODY = "[login verification code redacted]";

    private final MessageRepository messageRepo;
    private final OutboxEventRepository outboxRepo;
    private final CarrierPrefixRepository carrierPrefixRepo;
    private final ObjectMapper objectMapper;

    @Value("${app.security.otp.sender-mask:NIB}")
    private String senderMask;

    @Value("${app.security.otp.sms-template}")
    private String smsTemplate;

    @Transactional
    public void send(UUID userId, String mobileE164, String otp) {
        String body = smsTemplate.replace("{otp}", otp);
        Encoding encoding = SmsSegments.resolveEncoding(body, null);

        Message msg = messageRepo.save(Message.builder()
                // Authentication traffic is not workspace-scoped; see V032.
                .workspaceId(null)
                .toNumber(mobileE164)
                .body(REDACTED_BODY)
                .encoding(encoding.name())
                .segmentCount((short) SmsSegments.count(body, encoding))
                .resolvedCarrier(resolveCarrier(mobileE164))
                .source("OTP")
                .status("PENDING")
                .attempts((short) 0)
                .build());

        SendCommand cmd = SendCommand.builder()
                .messageId(msg.getId())
                .senderMask(senderMask)
                .to(mobileE164)
                .body(body)
                .encoding(encoding)
                .priority(Priority.OTP)
                .resolvedCarrier(msg.getResolvedCarrier())
                .issuedAt(Instant.now())
                .build();

        try {
            outboxRepo.save(OutboxEvent.builder()
                    .aggregateType("message")
                    .aggregateId(msg.getId())
                    .eventType("SendCommand")
                    .payload(objectMapper.writeValueAsString(cmd))
                    .createdAt(Instant.now())
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the OTP send command", e);
        }

        log.info("OTP SMS queued for user {} (messageId={})", userId, msg.getId());
    }

    private String resolveCarrier(String phone) {
        CarrierPrefix best = null;
        for (CarrierPrefix cp : carrierPrefixRepo.findAll()) {
            if (cp.isActive() && phone.startsWith(cp.getPrefix())
                    && (best == null || cp.getPrefix().length() > best.getPrefix().length())) {
                best = cp;
            }
        }
        return best != null ? best.getCarrier() : "UNKNOWN";
    }
}
