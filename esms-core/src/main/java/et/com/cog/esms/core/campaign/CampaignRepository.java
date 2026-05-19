package et.com.cog.esms.core.campaign;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    List<Campaign> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<Campaign> findByWorkspaceIdAndStatus(UUID workspaceId, String status);
}
