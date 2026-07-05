package et.com.cog.esms.core.audit;
import et.com.cog.esms.core.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
    @Autowired
    @Lazy
    private AuditService self;
    public void log(UUID workspaceId,
                    String category,
                    String severity,
                    String action,
                    String entityType,
                    UUID entityId) {
        UUID actorId = WorkspaceContext.currentUserId();
        String username = null;
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) username = auth.getName();
        } catch (Exception ignored) {}
        self.logAsync(workspaceId, category, severity, action, entityType, entityId, actorId, username);
    }
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAsync(UUID workspaceId,
                         String category,
                         String severity,
                         String action,
                         String entityType,
                         UUID entityId,
                         UUID actorUserId,
                         String actorUsername) {
        try {
            String prevHash = auditLogRepo.findFirstByOrderBySeqDesc()
                    .map(AuditLog::getRowHash)
                    .orElse(null);
            AuditLog entry = AuditLog.builder()
                    .workspaceId(workspaceId)
                    .actorUserId(actorUserId)
                    .actorUsername(actorUsername)
                    .category(category)
                    .severity(severity)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .prevHash(prevHash)
                    .build();
            String rowHash = calculateHash(entry);
            entry.setRowHash(rowHash);
            auditLogRepo.save(entry);
        } catch (Exception ignored) {}
    }
    private String calculateHash(AuditLog entry) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            String data = "" + entry.getWorkspaceId() + entry.getActorUserId() + entry.getCategory() + entry.getAction() + entry.getPrevHash();
            byte[] hash = digest.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString().replace("-", "");
        }
    }
}
