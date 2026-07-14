package et.com.cog.esms.core.reminder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReminderTemplateRepository extends JpaRepository<ReminderTemplate, UUID> {

    List<ReminderTemplate> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<ReminderTemplate> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID workspaceId, String status);
}
