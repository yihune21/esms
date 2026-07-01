package et.com.cog.esms.core.identity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserActivityLogRepository extends JpaRepository<UserActivityLog, UUID> {

    Page<UserActivityLog> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    Page<UserActivityLog> findByUserId(UUID userId, Pageable pageable);

    Page<UserActivityLog> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId, Pageable pageable);

    Page<UserActivityLog> findByWorkspaceIdAndCreatedAtBetween(UUID workspaceId, Instant from, Instant to, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT u.userId) FROM UserActivityLog u " +
           "WHERE (:wsId IS NULL OR u.workspaceId = :wsId) " +
           "AND u.createdAt >= :since")
    long countActiveUsersSince(@Param("wsId") UUID workspaceId, @Param("since") Instant since);

    @Query("SELECT u.action AS action, COUNT(u) AS total FROM UserActivityLog u " +
           "WHERE (:wsId IS NULL OR u.workspaceId = :wsId) " +
           "AND (:since IS NULL OR u.createdAt >= :since) " +
           "GROUP BY u.action ORDER BY total DESC")
    List<ActionFrequencyPoint> findActionFrequency(@Param("wsId") UUID workspaceId,
                                                    @Param("since") Instant since);

    interface ActionFrequencyPoint {
        String getAction();
        long   getTotal();
    }
}
