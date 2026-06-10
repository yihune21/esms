package et.com.cog.esms.core.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContactRepository extends JpaRepository<Contact, UUID> {

    List<Contact> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<Contact> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID workspaceId, String status);

    boolean existsByWorkspaceIdAndPhoneE164(UUID workspaceId, String phoneE164);

    java.util.Optional<Contact> findByWorkspaceIdAndPhoneE164(UUID workspaceId, String phoneE164);
}
