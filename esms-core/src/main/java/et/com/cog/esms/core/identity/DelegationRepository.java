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
           "AND d.startsAt <= :now AND (d.endsAt IS NULL OR d.endsAt >= :now) AND d.revoked = false")
    List<Delegation> findActiveDelegations(@Param("workspaceId") UUID workspaceId,
                                           @Param("toUserId") UUID toUserId,
                                           @Param("now") Instant now);

    /**
     * Finds active delegations for a given delegate user in a specific workspace.
     * Handles null endsAt (standing/no-expiry delegations).
     * Called by JwtAuthenticationFilter on every request to inject extra authorities.
     */
    @Query("SELECT d FROM Delegation d " +
           "WHERE d.toUserId = :toUserId " +
           "AND d.workspaceId = :workspaceId " +
           "AND d.revoked = false " +
           "AND d.startsAt <= :now " +
           "AND (d.endsAt IS NULL OR d.endsAt > :now)")
    List<Delegation> findActiveForDelegate(@Param("toUserId") UUID toUserId,
                                           @Param("workspaceId") UUID workspaceId,
                                           @Param("now") Instant now);

    /**
     * Variant without workspaceId — used when the user has no workspace in their JWT
     * (e.g. SUPER_ADMIN platform-level token). Returns all active incoming delegations
     * across all workspaces.
     */
    @Query("SELECT d FROM Delegation d " +
           "WHERE d.toUserId = :toUserId " +
           "AND d.revoked = false " +
           "AND d.startsAt <= :now " +
           "AND (d.endsAt IS NULL OR d.endsAt > :now)")
    List<Delegation> findActiveForDelegateAnyWorkspace(@Param("toUserId") UUID toUserId,
                                                       @Param("now") Instant now);
}
