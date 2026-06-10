package et.com.cog.esms.core.workspace;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

/**
 * M2M: workspace ↔ feature permission flags.
 * Each row represents a permission enabled for a workspace.
 * Matches the frontend workspace creation form checkboxes.
 */
@Entity
@Table(name = "workspace_permission")
@IdClass(WorkspacePermissionId.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WorkspacePermission {

    @Id
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Id
    @Column(name = "permission_code")
    private String permissionCode;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
class WorkspacePermissionId implements Serializable {
    private UUID workspaceId;
    private String permissionCode;
}
