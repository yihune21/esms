package et.com.cog.esms.core.contact;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Individual contact / address-book entry.
 * Reference: LLD §4.3 – contact table (V002)
 */
@Entity
@Table(name = "contact")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String name;

    @Column(name = "phone_e164", nullable = false)
    private String phoneE164;

    private String branch;

    @Column(name = "opt_out", nullable = false)
    private boolean optOut;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
