package et.com.cog.esms.core.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByCampaignId(UUID campaignId);

    List<Message> findByWorkspaceIdAndStatus(UUID workspaceId, String status);

    long countByCampaignIdAndStatusIn(UUID campaignId, List<String> terminalStatuses);

    long countByCampaignId(UUID campaignId);

    // ── Reporting queries ─────────────────────────────────────────────────

    long countByWorkspaceIdAndStatus(UUID workspaceId, String status);

    /**
     * Flexible filtered query for the delivery dashboard.
     * All filter params are optional (null = ignored).
     */
    /**
     * Flexible filtered query for the delivery dashboard.
     * All filter params are optional (null = ignored).
     * Branch is matched via an EXISTS sub-select on the Contact entity.
     */
    @Query("""
        SELECT m FROM Message m
        WHERE m.workspaceId = :wsId
          AND (:from       IS NULL OR m.createdAt  >= :from)
          AND (:to         IS NULL OR m.createdAt  <= :to)
          AND (:campaignId IS NULL OR m.campaignId  = :campaignId)
          AND (:status     IS NULL OR m.status      = :status)
          AND (:branch     IS NULL OR EXISTS (
                SELECT c FROM Contact c
                WHERE c.id = m.contactId AND c.branch = :branch))
        ORDER BY m.createdAt DESC
        """)
    List<Message> findFiltered(
            @Param("wsId")       UUID    workspaceId,
            @Param("from")       Instant from,
            @Param("to")         Instant to,
            @Param("campaignId") UUID    campaignId,
            @Param("status")     String  status,
            @Param("branch")     String  branch,
            Pageable pageable
    );

    @Query("""
        SELECT COUNT(m) FROM Message m
        WHERE m.workspaceId = :wsId
          AND (:from       IS NULL OR m.createdAt  >= :from)
          AND (:to         IS NULL OR m.createdAt  <= :to)
          AND (:campaignId IS NULL OR m.campaignId  = :campaignId)
          AND (:status     IS NULL OR m.status      = :status)
          AND (:branch     IS NULL OR EXISTS (
                SELECT c FROM Contact c
                WHERE c.id = m.contactId AND c.branch = :branch))
        """)
    long countFiltered(
            @Param("wsId")       UUID    workspaceId,
            @Param("from")       Instant from,
            @Param("to")         Instant to,
            @Param("campaignId") UUID    campaignId,
            @Param("status")     String  status,
            @Param("branch")     String  branch
    );
}
