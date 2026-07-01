package et.com.cog.esms.core.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TemplateRepository extends JpaRepository<Template, UUID> {

    List<Template> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<Template> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID workspaceId, String status);

    boolean existsByWorkspaceIdAndName(UUID workspaceId, String name);

    List<Template> findAllByOrderByCreatedAtDesc();

    List<Template> findByStatusOrderByCreatedAtDesc(String status);
}
