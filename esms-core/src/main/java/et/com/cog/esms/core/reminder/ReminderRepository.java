package et.com.cog.esms.core.reminder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

// A Reminder row is a RUN (one send of a reminder template). See ReminderService.
@Repository
public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    List<Reminder> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<Reminder> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID workspaceId, String status);

    // Platform-wide (super admin, no workspace context) — every workspace's runs.
    List<Reminder> findAllByOrderByCreatedAtDesc();

    List<Reminder> findByStatusOrderByCreatedAtDesc(String status);
}
