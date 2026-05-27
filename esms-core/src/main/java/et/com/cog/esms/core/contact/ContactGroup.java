package et.com.cog.esms.core.contact;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Named group / segment of contacts, used as campaign recipient lists.
 * Reference: LLD §4.3 – contact_group table (V002)
 */
@Entity
@Table(name = "contact_group")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ContactGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String name;

    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
