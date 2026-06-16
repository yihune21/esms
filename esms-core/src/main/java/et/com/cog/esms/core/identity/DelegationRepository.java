package et.com.cog.esms.core.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface DelegationRepository extends JpaRepository<Delegation, UUID> {

    List<Delegation> findByWorkspaceId(UUID workspaceId);

    List<Delegation> findByFromUserId(UUID fromUserId);

    List<Delegation> findByToUserId(UUID toUserId);

    @Query("SELECT d FROM Delegation d WHERE d.workspaceId = :workspaceId AND d.toUserId = :toUserId " +
           "AND d.startsAt <= :now AND d.endsAt >= :now AND d.revoked = false")
    List<Delegation> findActiveDelegations(@Param("workspaceId") UUID workspaceId,
                                           @Param("toUserId") UUID toUserId,
                                           @Param("now") Instant now);
}
