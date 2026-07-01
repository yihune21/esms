package et.com.cog.esms.core.reminder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    List<Reminder> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<Reminder> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID workspaceId, String status);


    @Query("SELECT r FROM Reminder r WHERE r.status = 'ACTIVE'")
    List<Reminder> findActiveReminders();
}
