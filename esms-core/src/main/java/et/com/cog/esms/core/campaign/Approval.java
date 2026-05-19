package et.com.cog.esms.core.campaign;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "approval")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Approval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "from_state", nullable = false)
    private String fromState;

    @Column(name = "to_state", nullable = false)
    private String toState;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "delegate_of")
    private UUID delegateOf;

    private String note;

    @Column(name = "snapshot_hash")
    private String snapshotHash;

    @Column(name = "created_at")
    private Instant createdAt;
}
