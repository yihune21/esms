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


    interface DailyTrendPoint {
        String getDay();
        String getStatus();
        long   getTotal();
    }

    interface CampaignSummaryPoint {
        UUID   getCampaignId();
        long   getSent();
        long   getDelivered();
        long   getFailed();
        long   getPending();
    }

    List<Message> findByCampaignId(UUID campaignId);

    List<Message> findByWorkspaceIdAndStatus(UUID workspaceId, String status);

    long countByCampaignIdAndStatusIn(UUID campaignId, List<String> terminalStatuses);

    long countByCampaignId(UUID campaignId);

    long countByWorkspaceId(UUID workspaceId);

    @Query("SELECT COUNT(DISTINCT m.workspaceId) FROM Message m")
    long countDistinctWorkspaces();


    @Query("SELECT COUNT(m) FROM Message m WHERE (:wsId IS NULL OR m.workspaceId = :wsId) AND m.status = :status")
    long countByWorkspaceIdAndStatus(@Param("wsId") UUID workspaceId, @Param("status") String status);

    
    @Query("""
        SELECT m FROM Message m
        WHERE (:wsId IS NULL OR m.workspaceId = :wsId)
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
        WHERE (:wsId IS NULL OR m.workspaceId = :wsId)
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

    
    @Query(value = """
        SELECT CAST(m.created_at AS date) AS day,
               m.status                  AS status,
               COUNT(*)                  AS total
        FROM message m
        WHERE (:allWorkspaces = true OR m.workspace_id = CAST(:wsId AS uuid))
          AND (:from IS NULL OR m.created_at >= :from)
          AND (:to   IS NULL OR m.created_at <= :to)
        GROUP BY CAST(m.created_at AS date), m.status
        ORDER BY CAST(m.created_at AS date)
        """, nativeQuery = true)
    List<DailyTrendPoint> findDailyTrend(
            @Param("wsId")          UUID    workspaceId,
            @Param("allWorkspaces") boolean allWorkspaces,
            @Param("from")          Instant from,
            @Param("to")            Instant to
    );

   
    @Query(value = """
        SELECT m.campaign_id                                             AS campaignId,
               COUNT(*) FILTER (WHERE m.status = 'SENT')                AS sent,
               COUNT(*) FILTER (WHERE m.status = 'DELIVERED')           AS delivered,
               COUNT(*) FILTER (WHERE m.status = 'FAILED')              AS failed,
               COUNT(*) FILTER (WHERE m.status IN ('PENDING','QUEUED')) AS pending
        FROM message m
        WHERE (:allWorkspaces = true OR m.workspace_id = CAST(:wsId AS uuid))
          AND m.campaign_id IS NOT NULL
          AND (:from IS NULL OR m.created_at >= :from)
          AND (:to   IS NULL OR m.created_at <= :to)
        GROUP BY m.campaign_id
        ORDER BY delivered DESC
        """, nativeQuery = true)
    List<CampaignSummaryPoint> findCampaignSummaries(
            @Param("wsId")          UUID    workspaceId,
            @Param("allWorkspaces") boolean allWorkspaces,
            @Param("from")          Instant from,
            @Param("to")            Instant to
    );
}
