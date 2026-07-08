package et.com.cog.esms.core.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.common.dto.SendCommand;
import et.com.cog.esms.common.enums.Encoding;
import et.com.cog.esms.common.enums.Priority;
import et.com.cog.esms.core.admin.CarrierPrefix;
import et.com.cog.esms.core.admin.CarrierPrefixRepository;
import et.com.cog.esms.core.contact.Contact;
import et.com.cog.esms.core.contact.ContactRepository;
import et.com.cog.esms.core.messaging.Message;
import et.com.cog.esms.core.messaging.MessageRepository;
import et.com.cog.esms.core.messaging.OutboxEvent;
import et.com.cog.esms.core.messaging.OutboxEventRepository;
import et.com.cog.esms.core.reminder.Reminder;
import et.com.cog.esms.core.reminder.ReminderRepository;
import et.com.cog.esms.core.template.Template;
import et.com.cog.esms.core.template.TemplateRecipient;
import et.com.cog.esms.core.template.TemplateRecipientRepository;
import et.com.cog.esms.core.template.TemplateRepository;
import et.com.cog.esms.core.workspace.Workspace;
import et.com.cog.esms.core.workspace.WorkspaceRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignDispatchService {

    private final CampaignRepository campaignRepo;
    private final ReminderRepository reminderRepo;
    private final ContactRepository contactRepo;
    private final TemplateRepository templateRepo;
    private final TemplateRecipientRepository templateRecipientRepo;
    private final CarrierPrefixRepository carrierPrefixRepo;
    private final MessageRepository messageRepo;
    private final OutboxEventRepository outboxRepo;
    private final WorkspaceRepository workspaceRepo;
    private final ObjectMapper objectMapper;

    @Data
    @AllArgsConstructor
    private static class RecipientInfo {
        private String phone;
        private String name;
        private UUID contactId;
        private Map<String, String> extra;
    }

    @Transactional
    public void dispatch(OutboxEvent outboxEvent) {
        log.info("Processing dispatch for event: id={}, type={}", outboxEvent.getId(), outboxEvent.getEventType());
        try {
            if ("ScheduledFire".equals(outboxEvent.getEventType())) {
                Map<String, Object> payload = objectMapper.readValue(outboxEvent.getPayload(), Map.class);
                UUID campaignId = UUID.fromString((String) payload.get("campaignId"));
                Campaign campaign = campaignRepo.findById(campaignId)
                        .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));
                dispatchCampaign(campaign);
            } else if ("ReminderFire".equals(outboxEvent.getEventType())) {
                Map<String, Object> payload = objectMapper.readValue(outboxEvent.getPayload(), Map.class);
                UUID reminderId = UUID.fromString((String) payload.get("reminderId"));
                Reminder reminder = reminderRepo.findById(reminderId)
                        .orElseThrow(() -> new IllegalArgumentException("Reminder not found: " + reminderId));
                dispatchReminder(reminder);
            }
        } catch (Exception e) {
            log.error("Failed to process outbox event dispatch: id={}", outboxEvent.getId(), e);
            throw new RuntimeException("Dispatch failed", e);
        }
    }

    private void dispatchCampaign(Campaign campaign) throws Exception {
        log.info("Dispatching campaign: id={}, name={}", campaign.getId(), campaign.getName());

        Template template = null;
        if (campaign.getTemplateId() != null) {
            template = templateRepo.findById(campaign.getTemplateId()).orElse(null);
        }

        String bodyTemplate = (campaign.getCustomBody() != null && !campaign.getCustomBody().trim().isEmpty())
                ? campaign.getCustomBody()
                : (template != null ? template.getBody() : "");

        String encoding = (template != null) ? template.getEncoding() : "GSM7";

        Workspace workspace = workspaceRepo.findById(campaign.getWorkspaceId()).orElse(null);
        String senderMask = (template != null && template.getSender() != null)
                ? template.getSender()
                : (workspace != null ? workspace.getSenderMask() : "NIB");

        Map<String, RecipientInfo> recipients = new HashMap<>();

        if (campaign.getRecipientGroupId() != null) {
            List<Contact> groupContacts = contactRepo.findActiveByGroupId(campaign.getRecipientGroupId());
            for (Contact c : groupContacts) {
                if (!c.isOptOut()) {
                    recipients.put(c.getPhoneE164(), new RecipientInfo(c.getPhoneE164(), c.getName(), c.getId(), c.getExtra()));
                }
            }
        }

        if (campaign.getUploadId() != null) {
            List<Contact> uploadContacts = contactRepo.findByUploadIdAndStatus(campaign.getUploadId(), "ACTIVE");
            for (Contact c : uploadContacts) {
                if (!c.isOptOut()) {
                    recipients.put(c.getPhoneE164(), new RecipientInfo(c.getPhoneE164(), c.getName(), c.getId(), c.getExtra()));
                }
            }
        }

        if (template != null) {
            if (template.getRecipientGroupId() != null) {
                List<Contact> templateGroupContacts = contactRepo.findActiveByGroupId(template.getRecipientGroupId());
                for (Contact c : templateGroupContacts) {
                    if (!c.isOptOut()) {
                        recipients.put(c.getPhoneE164(), new RecipientInfo(c.getPhoneE164(), c.getName(), c.getId(), c.getExtra()));
                    }
                }
            }
            List<TemplateRecipient> templateRecipients = templateRecipientRepo.findByTemplateId(template.getId());
            for (TemplateRecipient tr : templateRecipients) {
                if (!recipients.containsKey(tr.getPhoneE164())) {
                    recipients.put(tr.getPhoneE164(), new RecipientInfo(tr.getPhoneE164(), tr.getName(), null, null));
                }
            }
        }

        List<CarrierPrefix> prefixes = carrierPrefixRepo.findAll();

        int recipientCount = 0;
        for (RecipientInfo recipient : recipients.values()) {
            String resolvedBody = resolveMessageBody(bodyTemplate, recipient.getName(), recipient.getPhone(), recipient.getExtra());
            String carrier = resolveCarrier(recipient.getPhone(), prefixes);
            short segmentCount = calculateSegmentCount(resolvedBody, encoding);

            Message msg = Message.builder()
                    .workspaceId(campaign.getWorkspaceId())
                    .campaignId(campaign.getId())
                    .contactId(recipient.getContactId())
                    .toNumber(recipient.getPhone())
                    .body(resolvedBody)
                    .encoding(encoding)
                    .segmentCount(segmentCount)
                    .resolvedCarrier(carrier)
                    .source("CAMPAIGN")
                    .status("PENDING")
                    .attempts((short) 0)
                    .build();
            msg = messageRepo.save(msg);

            SendCommand cmd = SendCommand.builder()
                    .messageId(msg.getId())
                    .workspaceId(msg.getWorkspaceId())
                    .campaignId(campaign.getId().toString())
                    .senderMask(senderMask)
                    .to(recipient.getPhone())
                    .body(resolvedBody)
                    .encoding(Encoding.valueOf(encoding))
                    .priority(Priority.NORMAL)
                    .resolvedCarrier(carrier)
                    .issuedAt(Instant.now())
                    .build();

            OutboxEvent sendEvent = OutboxEvent.builder()
                    .aggregateType("message")
                    .aggregateId(msg.getId())
                    .eventType("SendCommand")
                    .payload(objectMapper.writeValueAsString(cmd))
                    .createdAt(Instant.now())
                    .build();
            outboxRepo.save(sendEvent);
            recipientCount++;
        }

        campaign.setRecipientCount(recipientCount);
        campaign.setStatus("QUEUED");
        campaign.setCompletedAt(Instant.now());
        campaignRepo.save(campaign);

        log.info("Campaign dispatch complete: id={}, messagesGenerated={}", campaign.getId(), recipientCount);
    }

    private void dispatchReminder(Reminder reminder) throws Exception {
        log.info("Evaluating reminder rule: id={}, name={}, triggerDays={}", reminder.getId(), reminder.getName(), reminder.getTriggerDays());

        Template template = templateRepo.findById(reminder.getTemplateId())
                .orElseThrow(() -> new IllegalArgumentException("Template not found for reminder: " + reminder.getTemplateId()));

        String bodyTemplate = (reminder.getCustomBody() != null && !reminder.getCustomBody().trim().isEmpty())
                ? reminder.getCustomBody()
                : template.getBody();

        String encoding = template.getEncoding();

        Workspace workspace = workspaceRepo.findById(reminder.getWorkspaceId()).orElse(null);
        String senderMask = (template.getSender() != null) ? template.getSender() : (workspace != null ? workspace.getSenderMask() : "NIB");

        Map<String, RecipientInfo> recipients = new HashMap<>();

        if (reminder.getRecipientGroupId() != null) {
            List<Contact> groupContacts = contactRepo.findActiveByGroupId(reminder.getRecipientGroupId());
            for (Contact c : groupContacts) {
                if (!c.isOptOut() && isDueForReminder(c, reminder.getTriggerDays())) {
                    recipients.put(c.getPhoneE164(), new RecipientInfo(c.getPhoneE164(), c.getName(), c.getId(), c.getExtra()));
                }
            }
        }

        if (reminder.getUploadId() != null) {
            List<Contact> uploadContacts = contactRepo.findByUploadIdAndStatus(reminder.getUploadId(), "ACTIVE");
            for (Contact c : uploadContacts) {
                if (!c.isOptOut() && isDueForReminder(c, reminder.getTriggerDays())) {
                    recipients.put(c.getPhoneE164(), new RecipientInfo(c.getPhoneE164(), c.getName(), c.getId(), c.getExtra()));
                }
            }
        }

        List<CarrierPrefix> prefixes = carrierPrefixRepo.findAll();

        int recipientCount = 0;
        for (RecipientInfo recipient : recipients.values()) {
            String resolvedBody = resolveMessageBody(bodyTemplate, recipient.getName(), recipient.getPhone(), recipient.getExtra());
            String carrier = resolveCarrier(recipient.getPhone(), prefixes);
            short segmentCount = calculateSegmentCount(resolvedBody, encoding);

            Message msg = Message.builder()
                    .workspaceId(reminder.getWorkspaceId())
                    .contactId(recipient.getContactId())
                    .toNumber(recipient.getPhone())
                    .body(resolvedBody)
                    .encoding(encoding)
                    .segmentCount(segmentCount)
                    .resolvedCarrier(carrier)
                    .source("AUTO_SCHEDULER")
                    .status("PENDING")
                    .attempts((short) 0)
                    .build();
            msg = messageRepo.save(msg);

            SendCommand cmd = SendCommand.builder()
                    .messageId(msg.getId())
                    .workspaceId(msg.getWorkspaceId())
                    .senderMask(senderMask)
                    .to(recipient.getPhone())
                    .body(resolvedBody)
                    .encoding(Encoding.valueOf(encoding))
                    .priority(Priority.NORMAL)
                    .resolvedCarrier(carrier)
                    .issuedAt(Instant.now())
                    .build();

            OutboxEvent sendEvent = OutboxEvent.builder()
                    .aggregateType("message")
                    .aggregateId(msg.getId())
                    .eventType("SendCommand")
                    .payload(objectMapper.writeValueAsString(cmd))
                    .createdAt(Instant.now())
                    .build();
            outboxRepo.save(sendEvent);
            recipientCount++;
        }

        reminder.setFiredAt(Instant.now());
        reminderRepo.save(reminder);

        log.info("Reminder dispatch complete: id={}, messagesGenerated={}", reminder.getId(), recipientCount);
    }

    private boolean isDueForReminder(Contact contact, int triggerDays) {
        LocalDate expiryDate = findAndParseExpiryDate(contact.getExtra());
        if (expiryDate == null) return false;
        long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
        return daysLeft == triggerDays;
    }

    private LocalDate findAndParseExpiryDate(Map<String, String> extra) {
        if (extra == null) return null;
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if (key.contains("expiry") || key.contains("expire")) {
                String val = entry.getValue();
                if (val == null || val.trim().isEmpty()) continue;
                LocalDate date = tryParseDate(val.trim());
                if (date != null) return date;
            }
        }
        return null;
    }

    private LocalDate tryParseDate(String dateStr) {
        int spaceIdx = dateStr.indexOf(' ');
        if (spaceIdx > 0) dateStr = dateStr.substring(0, spaceIdx);
        int tIdx = dateStr.indexOf('T');
        if (tIdx > 0) dateStr = dateStr.substring(0, tIdx);

        List<java.time.format.DateTimeFormatter> formatters = List.of(
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy"),
                java.time.format.DateTimeFormatter.ofPattern("MM-dd-yyyy")
        );

        for (java.time.format.DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(dateStr, formatter);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String resolveMessageBody(String bodyTemplate, String contactName, String contactPhone, Map<String, String> extra) {
        if (bodyTemplate == null) return "";
        String resolved = bodyTemplate;
        resolved = resolved.replace("${name}", contactName != null ? contactName : "");
        resolved = resolved.replace("${phone}", contactPhone != null ? contactPhone : "");
        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                String placeholder = "${" + entry.getKey() + "}";
                resolved = resolved.replace(placeholder, entry.getValue() != null ? entry.getValue() : "");
            }
        }
        resolved = resolved.replace("${Name}", contactName != null ? contactName : "");
        resolved = resolved.replace("${Phone}", contactPhone != null ? contactPhone : "");
        return resolved;
    }

    private String resolveCarrier(String phone, List<CarrierPrefix> prefixes) {
        if (phone == null || phone.isEmpty()) return "UNKNOWN";
        CarrierPrefix bestMatch = null;
        for (CarrierPrefix cp : prefixes) {
            if (cp.isActive() && phone.startsWith(cp.getPrefix())) {
                if (bestMatch == null || cp.getPrefix().length() > bestMatch.getPrefix().length()) {
                    bestMatch = cp;
                }
            }
        }
        return bestMatch != null ? bestMatch.getCarrier() : "UNKNOWN";
    }

    private short calculateSegmentCount(String body, String encoding) {
        if (body == null || body.isEmpty()) return 1;
        boolean isUcs2 = "UCS2".equalsIgnoreCase(encoding);
        int length = body.length();
        if (isUcs2) {
            if (length <= 70) return 1;
            return (short) Math.ceil((double) length / 67);
        } else {
            if (length <= 160) return 1;
            return (short) Math.ceil((double) length / 153);
        }
    }
}
