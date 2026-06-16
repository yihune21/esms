package et.com.cog.esms.core.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    List<Schedule> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<Schedule> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID workspaceId, String status);

    /**
     * Fetches all ACTIVE schedules.
     * Used by the @Scheduled polling job to process active reminders.
     */
    @Query("SELECT s FROM Schedule s WHERE s.status = 'ACTIVE'")
    List<Schedule> findActiveSchedules();
}
