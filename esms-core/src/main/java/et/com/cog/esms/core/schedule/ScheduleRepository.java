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

    List<Schedule> findByWorkspaceIdOrderByDueDateAsc(UUID workspaceId);

    List<Schedule> findByWorkspaceIdAndStatusOrderByDueDateAsc(UUID workspaceId, String status);

    /**
     * Fetches all PENDING schedules whose due date has arrived or passed.
     * Used by the @Scheduled polling job to fire reminders.
     */
    @Query("SELECT s FROM Schedule s WHERE s.status = 'PENDING' AND s.dueDate <= :today")
    List<Schedule> findPendingDueBy(@Param("today") LocalDate today);
}
