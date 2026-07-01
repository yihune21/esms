package et.com.cog.esms.core.template;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(
    name = "template_recipient",
    uniqueConstraints = @UniqueConstraint(columnNames = {"template_id", "phone_e164"})
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TemplateRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;


    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(length = 120)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
