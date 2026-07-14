package et.com.cog.esms.core.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ContactUploadLinkRepository
        extends JpaRepository<ContactUploadLink, ContactUploadLinkId> {

    boolean existsByUploadIdAndContactId(UUID uploadId, UUID contactId);
}
