package et.com.cog.esms.core.template;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Inline recipient attached directly to a template.
 *
 * A template can carry its own fixed list of recipients so it is
 * self-contained for small, known recipient sets (e.g. a "Board
 * Monthly Update" template that always goes to the same 5 numbers).
 *
 * {@code phoneE164} is mandatory — every recipient must have a phone number.
 * {@code name} is optional display metadata.
 *
 * When a campaign is launched from this template, esms-sender merges:
 *   - All {@code template_recipient} rows for the template
 *   - All members of the linked {@code contact_group} (if any)
 *
 * Maps to the {@code template_recipient} table (V017).
 */
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

    /**
     * Phone number in E.164 format (e.g. +251911234567).
     * Mandatory for every recipient.
     */
    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    /** Optional display name for the recipient. */
    @Column(length = 120)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
