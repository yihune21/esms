package et.com.cog.esms.core.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkspacePermissionRepository extends JpaRepository<WorkspacePermission, WorkspacePermissionId> {

    List<WorkspacePermission> findByWorkspaceId(UUID workspaceId);

    void deleteByWorkspaceId(UUID workspaceId);

    boolean existsByWorkspaceIdAndPermissionCode(UUID workspaceId, String permissionCode);
}
