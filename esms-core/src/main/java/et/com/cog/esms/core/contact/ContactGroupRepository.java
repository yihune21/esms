package et.com.cog.esms.core.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContactGroupRepository extends JpaRepository<ContactGroup, UUID> {

    List<ContactGroup> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<ContactGroup> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID workspaceId, String status);

    // Platform-wide (super admin, no workspace context) — every workspace's groups.
    List<ContactGroup> findAllByOrderByCreatedAtDesc();

    List<ContactGroup> findByStatusOrderByCreatedAtDesc(String status);

    boolean existsByWorkspaceIdAndName(UUID workspaceId, String name);
}
