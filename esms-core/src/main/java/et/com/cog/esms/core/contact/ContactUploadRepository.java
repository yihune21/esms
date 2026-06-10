package et.com.cog.esms.core.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContactUploadRepository extends JpaRepository<ContactUpload, UUID> {

    List<ContactUpload> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
}
