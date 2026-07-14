package et.com.cog.esms.core.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.common.dto.SendCommand;
import et.com.cog.esms.common.enums.Encoding;
import et.com.cog.esms.common.enums.Priority;
import et.com.cog.esms.core.admin.CarrierPrefix;
import et.com.cog.esms.core.admin.CarrierPrefixRepository;
import et.com.cog.esms.core.template.Template;
import et.com.cog.esms.core.template.TemplateRepository;
import et.com.cog.esms.core.workspace.Workspace;
import et.com.cog.esms.core.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manual re-send of messages whose delivery terminally failed. A retry keeps
 * the ORIGINAL message row (same id, same resolved body/carrier) so delivery
 * stats stay attached to the campaign/reminder it came from: the row flips
 * back to PENDING, attempts is bumped, and a fresh SendCommand goes through
 * the same outbox the first attempt used.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageRetryService {

    /** Only terminal failures may be retried — anything still in flight
     * (PENDING/QUEUED/SENT) or already DELIVERED must never be re-sent. */
    public static final List<String> RETRYABLE_STATUSES = List.of("FAILED", "EXPIRED");

    private final MessageRepository messageRepo;
    private final OutboxEventRepository outboxRepo;
    private final TemplateRepository templateRepo;
    private final WorkspaceRepository workspaceRepo;
    private final CarrierPrefixRepository carrierPrefixRepo;
    private final ObjectMapper objectMapper;

    public static boolean isRetryable(Message m) {
        return RETRYABLE_STATUSES.contains(m.getStatus());
    }

    /**
     * Corrects a single failed message's destination number (e.g. a typo in the
     * uploaded file) and re-sends it. Re-resolves the carrier for the new
     * number so routing stays correct.
     */
    @Transactional
    public void retryToNewNumber(Message m, String newNumber, UUID templateId, UUID workspaceId, String campaignId) {
        String normalized = normalizePhone(newNumber);
        m.setToNumber(normalized);
        m.setResolvedCarrier(resolveCarrier(normalized));
        messageRepo.save(m);
        retryMessages(List.of(m), templateId, workspaceId, campaignId);
    }

    /**
     * Re-enqueues every retryable message in the list. Non-retryable entries
     * are skipped silently so callers can pass a whole campaign's messages.
     *
     * @param templateId the campaign/reminder's message template (nullable) —
     *                   used to re-derive the sender mask the same way the
     *                   original dispatch did
     * @return how many messages were actually re-enqueued
     */
    @Transactional
    public int retryMessages(List<Message> messages, UUID templateId, UUID workspaceId, String campaignId) {
        String senderMask = senderMaskFor(templateId, workspaceId);
        int retried = 0;
        for (Message m : messages) {
            if (!isRetryable(m)) continue;

            m.setStatus("PENDING");
            m.setErrorCode(null);
            m.setAttempts((short) (m.getAttempts() + 1));
            messageRepo.save(m);

            SendCommand cmd = SendCommand.builder()
                    .messageId(m.getId())
                    .workspaceId(m.getWorkspaceId())
                    .campaignId(campaignId)
                    .senderMask(senderMask)
                    .to(m.getToNumber())
                    .body(m.getBody())
                    .encoding(Encoding.valueOf(m.getEncoding()))
                    .priority(Priority.NORMAL)
                    .resolvedCarrier(m.getResolvedCarrier())
                    .issuedAt(Instant.now())
                    .build();

            try {
                OutboxEvent sendEvent = OutboxEvent.builder()
                        .aggregateType("message")
                        .aggregateId(m.getId())
                        .eventType("SendCommand")
                        .payload(objectMapper.writeValueAsString(cmd))
                        .createdAt(Instant.now())
                        .build();
                outboxRepo.save(sendEvent);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize retry command", e);
            }
            retried++;
        }
        if (retried > 0) {
            log.info("Retry enqueued: {} message(s), workspace={}, campaign={}", retried, workspaceId, campaignId);
        }
        return retried;
    }

    // Same resolution order as CampaignDispatchService: template sender wins,
    // then the workspace's sender mask, then the platform default.
    private String senderMaskFor(UUID templateId, UUID workspaceId) {
        Template template = templateId != null ? templateRepo.findById(templateId).orElse(null) : null;
        if (template != null && template.getSender() != null) return template.getSender();
        Workspace workspace = workspaceId != null ? workspaceRepo.findById(workspaceId).orElse(null) : null;
        return (workspace != null && workspace.getSenderMask() != null) ? workspace.getSenderMask() : "NIB";
    }

    private String resolveCarrier(String phone) {
        if (phone == null || phone.isEmpty()) return "UNKNOWN";
        CarrierPrefix best = null;
        for (CarrierPrefix cp : carrierPrefixRepo.findAll()) {
            if (cp.isActive() && phone.startsWith(cp.getPrefix())
                    && (best == null || cp.getPrefix().length() > best.getPrefix().length())) {
                best = cp;
            }
        }
        return best != null ? best.getCarrier() : "UNKNOWN";
    }

    // Mirrors ExcelUploadService.normalizePhone so a corrected number entered by
    // hand ends up in the same E.164 shape as uploaded ones.
    private String normalizePhone(String phone) {
        if (phone == null) return "";
        phone = phone.replaceAll("[\\s\\-().]", "");
        if (phone.startsWith("09") || phone.startsWith("07")) phone = "+251" + phone.substring(1);
        if (phone.startsWith("251") && !phone.startsWith("+")) phone = "+" + phone;
        return phone;
    }
}
