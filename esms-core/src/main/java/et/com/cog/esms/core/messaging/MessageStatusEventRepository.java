package et.com.cog.esms.core.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for per-message DLR status event history.
 * Reference: LLD §4.4 – message_status_event table
 */
@Repository
public interface MessageStatusEventRepository extends JpaRepository<MessageStatusEvent, UUID> {

    List<MessageStatusEvent> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    List<MessageStatusEvent> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
}
