package et.com.cog.esms.core.reminder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import et.com.cog.esms.core.messaging.OutboxEvent;
import et.com.cog.esms.core.messaging.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;


 
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepository reminderRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;


    @Transactional
    public Reminder create(UUID workspaceId, String name, UUID recipientGroupId,
                           UUID uploadId, UUID templateId, String customBody,
                           String kind, int triggerDays) {
        if (templateId == null && (customBody == null || customBody.isBlank())) {
            throw new IllegalArgumentException("Either templateId or customBody must be provided");
        }
        Reminder r = Reminder.builder()
                .workspaceId(workspaceId)
                .name(name)
                .recipientGroupId(recipientGroupId)
                .uploadId(uploadId)
                .templateId(templateId)
                .customBody(customBody)
                .kind(kind != null ? kind : "CUSTOM")
                .triggerDays(triggerDays)
                .status("PENDING")
                .build();
        Reminder saved = reminderRepo.save(r);
        log.info("Reminder created: id={}, name={}, triggerDays={}", saved.getId(), name, triggerDays);
        return saved;
    }


    @Transactional
    public Reminder update(UUID workspaceId, UUID reminderId, Map<String, Object> updates) {
        Reminder r = getById(workspaceId, reminderId);

        if (updates.containsKey("name")) {
            r.setName((String) updates.get("name"));
        }
        if (updates.containsKey("recipientGroupId")) {
            Object val = updates.get("recipientGroupId");
            if (val == null) {
                r.setRecipientGroupId(null);
            } else {
                r.setRecipientGroupId(UUID.fromString(val.toString()));
            }
        }
        if (updates.containsKey("uploadId")) {
            Object val = updates.get("uploadId");
            if (val == null) {
                r.setUploadId(null);
            } else {
                r.setUploadId(UUID.fromString(val.toString()));
            }
        }
        if (updates.containsKey("templateId")) {
            Object val = updates.get("templateId");
            if (val == null) {
                r.setTemplateId(null);
            } else {
                r.setTemplateId(UUID.fromString(val.toString()));
            }
        }
        if (updates.containsKey("customBody")) {
            r.setCustomBody((String) updates.get("customBody"));
        }
        if (updates.containsKey("triggerDays")) {
            Object val = updates.get("triggerDays");
            if (val instanceof Number) {
                r.setTriggerDays(((Number) val).intValue());
            } else if (val instanceof String) {
                r.setTriggerDays(Integer.parseInt((String) val));
            }
        }
        if (updates.containsKey("kind")) {
            r.setKind((String) updates.get("kind"));
        }
        if (updates.containsKey("status")) {
            r.setStatus((String) updates.get("status"));
        }

        if (r.getTemplateId() == null && (r.getCustomBody() == null || r.getCustomBody().isBlank())) {
            throw new IllegalArgumentException("Either templateId or customBody must be provided");
        }

        Reminder saved = reminderRepo.save(r);
        log.info("Reminder updated: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }


    @Transactional(readOnly = true)
    public List<Reminder> list(UUID workspaceId, String status) {
        if (status != null && !status.isBlank()) {
            return reminderRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(workspaceId, status);
        }
        return reminderRepo.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

  
    @Transactional(readOnly = true)
    public Reminder getById(UUID workspaceId, UUID reminderId) {
        Reminder r = reminderRepo.findById(reminderId)
                .orElseThrow(() -> new IllegalArgumentException("Reminder not found: " + reminderId));
        if (!r.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalStateException("Reminder does not belong to this workspace");
        }
        return r;
    }


    @Transactional
    public Reminder deactivate(UUID workspaceId, UUID reminderId) {
        Reminder r = getById(workspaceId, reminderId);
        r.setStatus("CANCELLED");
        r.setCancelledAt(Instant.now());
        log.info("Reminder deactivated: id={}", reminderId);
        return reminderRepo.save(r);
    }


    @Transactional
    public Reminder activate(UUID workspaceId, UUID reminderId) {
        Reminder r = getById(workspaceId, reminderId);
        r.setStatus("PENDING");
        log.info("Reminder activated: id={}", reminderId);
        return reminderRepo.save(r);
    }

   
    @Transactional
    public void triggerNow(UUID workspaceId, UUID reminderId) {
        Reminder r = getById(workspaceId, reminderId);
        fireReminder(r);
        log.info("Reminder manually triggered: id={}", reminderId);
    }

   
    @Scheduled(cron = "${esms.scheduler.reminder-cron:0 0 8 * * *}")
    @Transactional
    public void processDueReminders() {
        List<Reminder> active = reminderRepo.findActiveReminders();
        if (active.isEmpty()) return;

        log.info("Reminder scheduler: evaluating {} active reminder rules", active.size());
        for (Reminder r : active) {
            try {
                fireReminder(r);
            } catch (Exception ex) {
                log.error("Failed to fire reminder id={}: {}", r.getId(), ex.getMessage(), ex);
            }
        }
    }


    private void fireReminder(Reminder r) {
       
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("reminderId",  r.getId().toString());
        payload.put("workspaceId", r.getWorkspaceId().toString());
        payload.put("triggerDays", r.getTriggerDays());
        if (r.getTemplateId() != null)       payload.put("templateId",        r.getTemplateId().toString());
        if (r.getCustomBody() != null)       payload.put("customBody",         r.getCustomBody());
        if (r.getRecipientGroupId() != null) payload.put("recipientGroupId",   r.getRecipientGroupId().toString());
        if (r.getUploadId() != null)         payload.put("uploadId",           r.getUploadId().toString());
        payload.put("kind", r.getKind());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize reminder payload", e);
        }

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("reminder")
                .aggregateId(r.getId())
                .eventType("ReminderFire")
                .payload(payloadJson)
                .build();
        outboxRepo.save(event);
        r.setStatus("FIRED");
        r.setFiredAt(Instant.now());
        log.info("Reminder OutboxEvent created: id={}, triggerDays={}", r.getId(), r.getTriggerDays());
    }
}
