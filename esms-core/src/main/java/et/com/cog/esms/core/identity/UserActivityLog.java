package et.com.cog.esms.core.identity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight activity ledger — records key actions taken by users.
 * Backed by user_activity_log table (V015).
 */
@Entity
@Table(name = "user_activity_log")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class UserActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** e.g. LOGIN, CAMPAIGN_CREATE, TEMPLATE_APPROVE, WORKSPACE_SWITCH */
    @Column(nullable = false, length = 80)
    private String action;

    /** Resource category: campaign, template, workspace, user … */
    @Column(length = 80)
    private String resource;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
