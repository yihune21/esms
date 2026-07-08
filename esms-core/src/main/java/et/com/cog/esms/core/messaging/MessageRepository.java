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

    // JPQL (entity field names / camelCase — correct as-is)
    @Query("SELECT COUNT(DISTINCT m.workspaceId) FROM Message m")
    long countDistinctWorkspaces();


    // JPQL (entity field names / camelCase — correct as-is)
    @Query("SELECT COUNT(m) FROM Message m WHERE (:wsId IS NULL OR m.workspaceId = :wsId) AND m.status = :status")
    long countByWorkspaceIdAndStatus(@Param("wsId") UUID workspaceId, @Param("status") String status);


    // NATIVE query — real DB column names (snake_case), AND each named parameter
    // is cast to its target type on EVERY occurrence (including the "IS NULL"
    // checks), not just the comparison side. Postgres cannot infer a bind
    // parameter's type from a bare "? IS NULL" check alone — without an explicit
    // cast there too, it throws "could not determine data type of parameter $N".
    @Query(value = """
        SELECT m.* FROM message m
        WHERE (CAST(CAST(:wsId AS text) AS uuid) IS NULL OR m.workspace_id = CAST(CAST(:wsId AS text) AS uuid))
          AND (CAST(:from AS timestamptz) IS NULL OR m.created_at >= CAST(:from AS timestamptz))
          AND (CAST(:to AS timestamptz) IS NULL OR m.created_at <= CAST(:to AS timestamptz))
          AND (CAST(CAST(:campaignId AS text) AS uuid) IS NULL OR m.campaign_id = CAST(CAST(:campaignId AS text) AS uuid))
          AND (CAST(:status AS text) IS NULL OR m.status = CAST(:status AS text))
          AND (CAST(:branch AS text) IS NULL OR EXISTS (
                SELECT 1 FROM contact c 
                WHERE c.id = m.contact_id AND c.branch = CAST(:branch AS text)))
        ORDER BY m.created_at DESC
        """, 
        countQuery = """
        SELECT COUNT(*) FROM message m
        WHERE (CAST(CAST(:wsId AS text) AS uuid) IS NULL OR m.workspace_id = CAST(CAST(:wsId AS text) AS uuid))
          AND (CAST(:from AS timestamptz) IS NULL OR m.created_at >= CAST(:from AS timestamptz))
          AND (CAST(:to AS timestamptz) IS NULL OR m.created_at <= CAST(:to AS timestamptz))
          AND (CAST(CAST(:campaignId AS text) AS uuid) IS NULL OR m.campaign_id = CAST(CAST(:campaignId AS text) AS uuid))
          AND (CAST(:status AS text) IS NULL OR m.status = CAST(:status AS text))
          AND (CAST(:branch AS text) IS NULL OR EXISTS (
                SELECT 1 FROM contact c 
                WHERE c.id = m.contact_id AND c.branch = CAST(:branch AS text)))
        """,
        nativeQuery = true)
    List<Message> findFiltered(
            @Param("wsId")       UUID    workspaceId,
            @Param("from")       Instant from,
            @Param("to")         Instant to,
            @Param("campaignId") UUID    campaignId,
            @Param("status")     String  status,
            @Param("branch")     String  branch,
            Pageable pageable
    );

    // JPQL (entity field names / camelCase — correct); "instant" is not a valid
    // JPQL cast target — use "timestamp" instead.
    @Query("""
        SELECT COUNT(m) FROM Message m
        WHERE (CAST(:wsId AS uuid) IS NULL OR m.workspaceId = :wsId)
          AND (CAST(:from AS timestamp) IS NULL OR m.createdAt >= :from)
          AND (CAST(:to AS timestamp) IS NULL OR m.createdAt <= :to)
          AND (CAST(:campaignId AS uuid) IS NULL OR m.campaignId = :campaignId)
          AND (CAST(:status AS string) IS NULL OR m.status = :status)
          AND (CAST(:branch AS string) IS NULL OR EXISTS (
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


    // NATIVE query — same fix: cast every occurrence of each named parameter.
    @Query(value = """
        SELECT CAST(m.created_at AS date) AS day,
               m.status                   AS status,
               COUNT(*)                   AS total
        FROM message m
        WHERE (CAST(CAST(:wsId AS text) AS uuid) IS NULL OR m.workspace_id = CAST(CAST(:wsId AS text) AS uuid))
          AND (CAST(:from AS timestamptz) IS NULL OR m.created_at >= CAST(:from AS timestamptz))
          AND (CAST(:to AS timestamptz) IS NULL OR m.created_at <= CAST(:to AS timestamptz))
        GROUP BY CAST(m.created_at AS date), m.status
        ORDER BY CAST(m.created_at AS date)
        """, nativeQuery = true)
    List<DailyTrendPoint> findDailyTrend(
            @Param("wsId")  UUID    workspaceId,
            @Param("from")  Instant from,
            @Param("to")    Instant to
    );


    // NATIVE query — same fix: cast every occurrence of each named parameter.
    @Query(value = """
        SELECT m.campaign_id                                            AS campaignId,
               COUNT(*) FILTER (WHERE m.status = 'SENT')                AS sent,
               COUNT(*) FILTER (WHERE m.status = 'DELIVERED')           AS delivered,
               COUNT(*) FILTER (WHERE m.status = 'FAILED')              AS failed,
               COUNT(*) FILTER (WHERE m.status IN ('PENDING','QUEUED')) AS pending
        FROM message m
        WHERE (CAST(CAST(:wsId AS text) AS uuid) IS NULL OR m.workspace_id = CAST(CAST(:wsId AS text) AS uuid))
          AND m.campaign_id IS NOT NULL
          AND (CAST(:from AS timestamptz) IS NULL OR m.created_at >= CAST(:from AS timestamptz))
          AND (CAST(:to AS timestamptz) IS NULL OR m.created_at <= CAST(:to AS timestamptz))
        GROUP BY m.campaign_id
        ORDER BY delivered DESC
        """, nativeQuery = true)
    List<CampaignSummaryPoint> findCampaignSummaries(
            @Param("wsId")  UUID    workspaceId,
            @Param("from")  Instant from,
            @Param("to")    Instant to
    );
}