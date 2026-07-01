package et.com.cog.esms.core.campaign;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    List<Campaign> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<Campaign> findByWorkspaceIdAndStatus(UUID workspaceId, String status);

    List<Campaign> findByStatus(String status);

   
    @Query("SELECT c FROM Campaign c WHERE c.kind = 'SCHEDULED' AND c.status = 'APPROVED' AND c.scheduledAt <= :now")
    List<Campaign> findDueScheduledCampaigns(@Param("now") Instant now);
}
