package et.com.cog.esms.core.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;


@Repository
public interface ReportExportRepository extends JpaRepository<ReportExport, UUID> {

    List<ReportExport> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
}
