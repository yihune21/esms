package et.com.cog.esms.core.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;


@Repository
public interface MessageStatusEventRepository extends JpaRepository<MessageStatusEvent, UUID> {

    List<MessageStatusEvent> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    List<MessageStatusEvent> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
}
