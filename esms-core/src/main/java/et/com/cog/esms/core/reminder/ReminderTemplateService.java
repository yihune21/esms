package et.com.cog.esms.core.reminder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import et.com.cog.esms.core.security.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderTemplateService {

    private final ReminderTemplateRepository templateRepo;

    @Transactional
    public ReminderTemplate create(UUID workspaceId, String name, UUID messageTemplateId,
                                   String customBody, String kind, int triggerDays) {
        if (messageTemplateId == null && (customBody == null || customBody.isBlank())) {
            throw new IllegalArgumentException("Either a message template or a custom message must be provided");
        }
        ReminderTemplate t = ReminderTemplate.builder()
                .workspaceId(workspaceId)
                .name(name)
                .templateId(messageTemplateId)
                .customBody(customBody)
                .kind(kind != null ? kind : "CUSTOM")
                .triggerDays(triggerDays)
                .status("ACTIVE")
                .createdBy(WorkspaceContext.currentUserId())
                .build();
        ReminderTemplate saved = templateRepo.save(t);
        log.info("Reminder template created: id={}, name={}", saved.getId(), name);
        return saved;
    }

    @Transactional
    public ReminderTemplate update(UUID workspaceId, UUID id, Map<String, Object> updates) {
        ReminderTemplate t = getById(workspaceId, id);

        if (updates.containsKey("name")) {
            t.setName((String) updates.get("name"));
        }
        if (updates.containsKey("customBody")) {
            t.setCustomBody((String) updates.get("customBody"));
        }
        if (updates.containsKey("templateId")) {
            Object val = updates.get("templateId");
            t.setTemplateId(val == null ? null : UUID.fromString(val.toString()));
        }
        if (updates.containsKey("triggerDays")) {
            Object val = updates.get("triggerDays");
            if (val instanceof Number) {
                t.setTriggerDays(((Number) val).intValue());
            } else if (val instanceof String) {
                t.setTriggerDays(Integer.parseInt((String) val));
            }
        }
        if (updates.containsKey("kind")) {
            t.setKind((String) updates.get("kind"));
        }
        // status is only moved via activate()/deactivate(), not a plain PATCH.

        if (t.getTemplateId() == null && (t.getCustomBody() == null || t.getCustomBody().isBlank())) {
            throw new IllegalArgumentException("Either a message template or a custom message must be provided");
        }

        ReminderTemplate saved = templateRepo.save(t);
        log.info("Reminder template updated: id={}", saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ReminderTemplate> list(UUID workspaceId, String status) {
        if (status != null && !status.isBlank()) {
            return templateRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(workspaceId, status);
        }
        return templateRepo.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    @Transactional(readOnly = true)
    public ReminderTemplate getById(UUID workspaceId, UUID id) {
        ReminderTemplate t = templateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Reminder template not found: " + id));
        if (!t.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalStateException("Reminder template does not belong to this workspace");
        }
        return t;
    }

    @Transactional
    public ReminderTemplate deactivate(UUID workspaceId, UUID id) {
        ReminderTemplate t = getById(workspaceId, id);
        t.setStatus("INACTIVE");
        log.info("Reminder template deactivated: id={}", id);
        return templateRepo.save(t);
    }

    @Transactional
    public ReminderTemplate activate(UUID workspaceId, UUID id) {
        ReminderTemplate t = getById(workspaceId, id);
        t.setStatus("ACTIVE");
        log.info("Reminder template activated: id={}", id);
        return templateRepo.save(t);
    }
}
