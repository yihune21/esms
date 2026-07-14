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

    // Resolves an upload's contacts through the contact_upload_link join table
    // (not the legacy contact.upload_id column) so a contact re-seen in a later
    // upload still stays reachable from THIS upload's campaign/reminder. Keeps
    // the same signature so every caller (dispatch, recipient preview, counts)
    // is fixed at once.
    @org.springframework.data.jpa.repository.Query(
        "SELECT c FROM Contact c WHERE c.status = :status AND c.id IN " +
        "(SELECT l.contactId FROM ContactUploadLink l WHERE l.uploadId = :uploadId)"
    )
    List<Contact> findByUploadIdAndStatus(
        @org.springframework.data.repository.query.Param("uploadId") UUID uploadId,
        @org.springframework.data.repository.query.Param("status") String status);
}
