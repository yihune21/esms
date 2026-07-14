package et.com.cog.esms.core.workspace;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String status;

    private String division;

    // Branch hierarchy. A "branch" (child) sets parentWorkspaceId to its
    // umbrella and behaves like a standalone workspace (own members, campaigns,
    // contacts) while inheriting the umbrella's feature permissions. An umbrella
    // (isBranchParent = true) owns branches and holds no data of its own; it
    // must always have ≥1 branch.
    @Column(name = "parent_workspace_id")
    private UUID parentWorkspaceId;

    @Column(name = "is_branch_parent", nullable = false)
    @Builder.Default
    private boolean isBranchParent = false;

    @Column(name = "sender_mask")
    private String senderMask;

    @Column(name = "daily_sms_limit")
    private Integer dailySmsLimit;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
