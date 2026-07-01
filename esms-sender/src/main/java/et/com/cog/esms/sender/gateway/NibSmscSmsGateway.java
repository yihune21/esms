package et.com.cog.esms.sender.gateway;

import et.com.cog.esms.common.dto.StatusEvent;
import et.com.cog.esms.common.enums.Carrier;
import et.com.cog.esms.common.enums.MessageStatus;
import et.com.cog.esms.sender.config.SmppProperties;
import et.com.cog.esms.sender.publisher.StatusEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.PDUException;
import org.jsmpp.bean.*;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.jsmpp.extra.SessionState;
import org.jsmpp.session.*;
import org.jsmpp.util.DeliveryReceiptState;
import org.jsmpp.util.InvalidDeliveryReceiptException;
import org.jsmpp.util.MessageId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


@Slf4j
@Component
@EnableConfigurationProperties(SmppProperties.class)
@RequiredArgsConstructor
public class NibSmscSmsGateway implements SmsGateway {

    private static final int MAX_GSM7  = 160;
    private static final int MAX_UCS2  = 70;
    private static final int SEG_GSM7  = 153;
    private static final int SEG_UCS2  = 67;

    private final SmppProperties     props;
    private final StatusEventPublisher statusPublisher;

    private final AtomicReference<SMPPSession> sessionRef    = new AtomicReference<>();
    private final AtomicBoolean                running       = new AtomicBoolean(false);
    private final AtomicBoolean                reconnecting  = new AtomicBoolean(false);
    private final AtomicInteger                reconnectCount = new AtomicInteger(0);
    private final ScheduledExecutorService     scheduler     =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "smpp-reconnect");
                t.setDaemon(true);
                return t;
            });

 
    @PostConstruct
    public void connect() {
        running.set(true);
        log.info("NibSmscSmsGateway starting — host={}:{} systemId={}",
                props.getHost(), props.getPort(), props.getSystemId());
        bindSession();
    }

    @PreDestroy
    public void disconnect() {
        running.set(false);
        scheduler.shutdownNow();
        SMPPSession session = sessionRef.getAndSet(null);
        if (session != null) {
            try {
                session.unbindAndClose();
                log.info("SMPP session unbound and closed cleanly.");
            } catch (Exception e) {
                log.warn("SMPP unbind error: {}", e.getMessage());
                session.close();
            }
        }
    }


    @Override
    public SendResult sendSms(SendRequest request) {
        SMPPSession session = sessionRef.get();
        if (session == null || !isBound(session)) {
            log.warn("SMPP session not bound — cannot send msgId={}", request.messageId());
            return new SendResult(false, null, "SESSION_NOT_BOUND",
                    "SMPP session is not connected to NIB SMSC");
        }

        try {
            boolean useUcs2 = needsUcs2(request.body());
            Alphabet alphabet = useUcs2 ? Alphabet.ALPHA_UCS2 : Alphabet.ALPHA_DEFAULT;
            int     maxLen  = useUcs2 ? MAX_UCS2 : MAX_GSM7;

            String senderId = (request.senderId() != null && !request.senderId().isBlank())
                    ? request.senderId() : props.getDefaultSenderId();

            if (request.body().length() <= maxLen) {
                String carrierId = submitSingle(session, request, senderId, alphabet, useUcs2);
                log.info("SMPP submit_sm OK — msgId={} carrierId={} to={}",
                        request.messageId(), carrierId, request.to());
                return new SendResult(true, carrierId, null, null);
            } else {
                String carrierId = submitMultiPart(session, request, senderId, alphabet, useUcs2);
                log.info("SMPP multi-part submit OK — msgId={} to={}",
                        request.messageId(), request.to());
                return new SendResult(true, carrierId, null, null);
            }

        } catch (PDUException e) {
            log.error("SMPP PDU error msgId={}: {}", request.messageId(), e.getMessage());
            return new SendResult(false, null, "PDU_ERROR", e.getMessage());
        } catch (ResponseTimeoutException e) {
            log.error("SMPP timeout msgId={}", request.messageId());
            scheduleReconnect();
            return new SendResult(false, null, "RESPONSE_TIMEOUT", e.getMessage());
        } catch (InvalidResponseException e) {
            log.error("SMPP invalid response msgId={}: {}", request.messageId(), e.getMessage());
            return new SendResult(false, null, "INVALID_RESPONSE", e.getMessage());
        } catch (NegativeResponseException e) {
            String code = "NACK_" + Integer.toHexString(e.getCommandStatus());
            log.error("SMPP NACK msgId={} status={}", request.messageId(), code);
            return new SendResult(false, null, code, e.getMessage());
        } catch (IOException e) {
            log.error("SMPP IO error msgId={}: {}", request.messageId(), e.getMessage());
            scheduleReconnect();
            return new SendResult(false, null, "IO_ERROR", e.getMessage());
        }
    }

    @Override
    public List<SendResult> sendBulk(List<SendRequest> requests) {
        List<SendResult> results = new ArrayList<>(requests.size());
        for (SendRequest req : requests) {
            results.add(sendSms(req));
        }
        return results;
    }

    @Override
    public Carrier carrier() {
        return Carrier.NIB_SMSC;
    }


    @Override
    public StatusEvent translateDlr(Map<String, Object> carrierPayload) {
        log.warn("translateDlr() called on NibSmscSmsGateway — DLRs arrive via SMPP deliver_sm");
        return null;
    }

    @Override
    public HealthStatus health() {
        SMPPSession session = sessionRef.get();
        if (session != null && isBound(session)) return HealthStatus.HEALTHY;
        return reconnecting.get() ? HealthStatus.DEGRADED : HealthStatus.UNAVAILABLE;
    }


    private void bindSession() {
        if (!running.get()) return;
        try {
            log.info("SMPP connecting to {}:{} systemId='{}'",
                    props.getHost(), props.getPort(), props.getSystemId());

            SMPPSession session = new SMPPSession();
            session.setMessageReceiverListener(new SmppDeliveryReceiptListener());
            session.addSessionStateListener(new SmppSessionStateListener());
            session.setEnquireLinkTimer(props.getEnquireLinkTimer() * 1000);

            session.connectAndBind(
                    props.getHost(),
                    props.getPort(),
                    new BindParameter(
                            BindType.BIND_TRX,
                            props.getSystemId(),
                            props.getPassword(),
                            props.getSystemType(),
                            TypeOfNumber.UNKNOWN,
                            NumberingPlanIndicator.UNKNOWN,
                            props.getAddressRange()
                    ),
                    (int) props.getBindTimeoutMs()
            );

            SMPPSession old = sessionRef.getAndSet(session);
            if (old != null) { try { old.close(); } catch (Exception ignored) {} }

            reconnecting.set(false);
            reconnectCount.set(0);
            log.info("SMPP session bound — sessionId={}", session.getSessionId());

        } catch (IOException e) {
            log.error("SMPP bind failed: {} — retrying in {}ms",
                    e.getMessage(), props.getReconnectDelayMs());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get() || reconnecting.getAndSet(true)) return;

        int  attempts   = reconnectCount.incrementAndGet();
        int  maxAttempts = props.getMaxReconnectAttempts();
        if (maxAttempts > 0 && attempts > maxAttempts) {
            log.error("SMPP: max reconnect attempts ({}) reached — giving up.", maxAttempts);
            reconnecting.set(false);
            return;
        }

        long delayMs = props.getReconnectDelayMs();
        log.warn("SMPP scheduling reconnect attempt #{} in {}ms", attempts, delayMs);
        scheduler.schedule(() -> {
            reconnecting.set(false);
            bindSession();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private boolean isBound(SMPPSession session) {
        SessionState s = session.getSessionState();
        return s == SessionState.BOUND_TRX
                || s == SessionState.BOUND_TX
                || s == SessionState.BOUND_RX;
    }


    private String submitSingle(SMPPSession session, SendRequest req,
                                String senderId, Alphabet alphabet, boolean useUcs2)
            throws PDUException, ResponseTimeoutException,
                   InvalidResponseException, NegativeResponseException, IOException {

        return session.submitShortMessage(
                props.getSystemType(),
                TypeOfNumber.valueOf(props.getSourceAddressTon()),
                NumberingPlanIndicator.valueOf(props.getSourceAddressNpi()),
                senderId,
                TypeOfNumber.valueOf(props.getDestAddressTon()),
                NumberingPlanIndicator.valueOf(props.getDestAddressNpi()),
                req.to(),
                new ESMClass(),
                (byte) 0,    // protocol id
                (byte) 1,    // priority flag
                null,        // schedule delivery time
                null,        // validity period
                new RegisteredDelivery(SMSCDeliveryReceipt.SUCCESS_FAILURE),
                (byte) 0,    // replace if present
                new GeneralDataCoding(alphabet),
                (byte) 0,    // sm default msg id
                req.body().getBytes(useUcs2 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1)
        ).getMessageId();
    }

    private String submitMultiPart(SMPPSession session, SendRequest req,
                                   String senderId, Alphabet alphabet, boolean useUcs2)
            throws PDUException, ResponseTimeoutException,
                   InvalidResponseException, NegativeResponseException, IOException {

        int    segMax    = useUcs2 ? SEG_UCS2 : SEG_GSM7;
        String body      = req.body();
        int    totalSegs = (int) Math.ceil((double) body.length() / segMax);
        short  sarRef    = (short) (Math.random() * 0xFFFF);
        String lastId    = null;

        for (int i = 0; i < totalSegs; i++) {
            String seg = body.substring(i * segMax, Math.min((i + 1) * segMax, body.length()));

            OptionalParameter sarMsgRef = new OptionalParameter.Short(
                    OptionalParameter.Tag.SAR_MSG_REF_NUM.code(), sarRef);
            OptionalParameter sarTotal  = new OptionalParameter.Byte(
                    OptionalParameter.Tag.SAR_TOTAL_SEGMENTS.code(), (byte) totalSegs);
            OptionalParameter sarSeq    = new OptionalParameter.Byte(
                    OptionalParameter.Tag.SAR_SEGMENT_SEQNUM.code(), (byte) (i + 1));

            lastId = session.submitShortMessage(
                    props.getSystemType(),
                    TypeOfNumber.valueOf(props.getSourceAddressTon()),
                    NumberingPlanIndicator.valueOf(props.getSourceAddressNpi()),
                    senderId,
                    TypeOfNumber.valueOf(props.getDestAddressTon()),
                    NumberingPlanIndicator.valueOf(props.getDestAddressNpi()),
                    req.to(),
                    new ESMClass(),
                    (byte) 0, (byte) 1, null, null,
                    new RegisteredDelivery(SMSCDeliveryReceipt.SUCCESS_FAILURE),
                    (byte) 0,
                    new GeneralDataCoding(alphabet),
                    (byte) 0,
                    seg.getBytes(useUcs2 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1),
                    sarMsgRef, sarTotal, sarSeq
            ).getMessageId();
        }
        return lastId;
    }


    private boolean needsUcs2(String text) {
        final String gsm7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./"
                + "0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "ÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
        for (char c : text.toCharArray()) {
            if (gsm7.indexOf(c) == -1) return true;
        }
        return false;
    }

    private class SmppDeliveryReceiptListener implements MessageReceiverListener {

        @Override
        public void onAcceptDeliverSm(DeliverSm deliverSm) throws ProcessRequestException {
            if (MessageType.SMSC_DEL_RECEIPT.containedIn(deliverSm.getEsmClass())) {
                handleReceipt(deliverSm);
            } else {
                log.debug("MO message from {} — ignoring (outbound-only platform)",
                        deliverSm.getSourceAddr());
            }
        }

        @Override
        public void onAcceptAlertNotification(AlertNotification alertNotification) {
            log.debug("SMPP alert notification received");
        }

        @Override
        public DataSmResult onAcceptDataSm(DataSm dataSm, Session source)
                throws ProcessRequestException {
            try {
                return new DataSmResult(new MessageId("1"), new OptionalParameter[0]);
            } catch (org.jsmpp.PDUStringException e) {
                throw new ProcessRequestException(e.getMessage(), 255);
            }
        }

        private void handleReceipt(DeliverSm deliverSm) {
            try {
                DeliveryReceipt receipt =
                        deliverSm.getShortMessageAsDeliveryReceipt();

                String              smppMsgId = receipt.getId();
                DeliveryReceiptState stat      = receipt.getFinalStatus();
                MessageStatus       status    = toMessageStatus(stat);

                log.info("SMPP DLR — smppMsgId={} stat={} → {}", smppMsgId, stat, status);

                statusPublisher.publish(StatusEvent.builder()
                        .carrierMsgId(smppMsgId)
                        .status(status)
                        .carrier(Carrier.NIB_SMSC.name())
                        .timestamp(Instant.now())
                        .build());

            } catch (InvalidDeliveryReceiptException e) {
                log.error("Failed to parse SMPP delivery receipt: {}", e.getMessage());
            }
        }

        
        private MessageStatus toMessageStatus(DeliveryReceiptState state) {
            return switch (state) {
                case DELIVRD           -> MessageStatus.DELIVERED;
                case ENROUTE, ACCEPTD  -> MessageStatus.SENT;
                default                -> MessageStatus.FAILED;
            };
        }
    }


    private class SmppSessionStateListener implements SessionStateListener {
        @Override
        public void onStateChange(SessionState newState, SessionState oldState, Session source) {
            log.info("SMPP state: {} → {}", oldState, newState);
            if (newState == SessionState.CLOSED || newState == SessionState.UNBOUND) {
                log.warn("SMPP session dropped — scheduling reconnect");
                scheduleReconnect();
            }
        }
    }
}
