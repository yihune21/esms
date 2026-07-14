package et.com.cog.esms.core.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findByCode(String code);

    boolean existsByCode(String code);

    List<Workspace> findByParentWorkspaceId(UUID parentWorkspaceId);
}
