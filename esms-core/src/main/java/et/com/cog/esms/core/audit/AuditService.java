package et.com.cog.esms.core.audit;

import et.com.cog.esms.core.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepo;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(UUID workspaceId,
                    String category,
                    String severity,
                    String action,
                    String entityType,
                    UUID entityId) {
        try {
            UUID actorId = WorkspaceContext.currentUserId();
            String username = null;
            try {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null) username = auth.getName();
            } catch (Exception ignored) {}

            AuditLog entry = AuditLog.builder()
                    .workspaceId(workspaceId)
                    .actorUserId(actorId)
                    .actorUsername(username)
                    .category(category)
                    .severity(severity)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .build();
            auditLogRepo.save(entry);
        } catch (Exception ignored) {}
    }
}
