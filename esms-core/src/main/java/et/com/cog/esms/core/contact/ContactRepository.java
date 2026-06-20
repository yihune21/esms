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

    @org.springframework.data.jpa.repository.Query(
        "SELECT c FROM Contact c JOIN ContactGroupMember m ON c.id = m.contactId WHERE m.groupId = :groupId AND c.status = 'ACTIVE'"
    )
    List<Contact> findActiveByGroupId(@org.springframework.data.repository.query.Param("groupId") UUID groupId);

    List<Contact> findByUploadIdAndStatus(UUID uploadId, String status);
}
