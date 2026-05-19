package et.com.cog.esms.core.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByCampaignId(UUID campaignId);

    List<Message> findByWorkspaceIdAndStatus(UUID workspaceId, String status);

    long countByCampaignIdAndStatusIn(UUID campaignId, List<String> terminalStatuses);

    long countByCampaignId(UUID campaignId);
}
