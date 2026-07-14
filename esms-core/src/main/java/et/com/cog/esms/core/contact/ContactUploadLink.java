package et.com.cog.esms.core.contact;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Many-to-many link between an upload and the contacts it referenced. A single
 * phone number legitimately appears in many uploads over time (daily policy
 * files), so a contact must be reachable from EVERY upload it was part of —
 * not just the most recent one. This replaces the old single upload_id column
 * on contact as the source of truth for "who does this upload's campaign send
 * to".
 */
@Entity
@Table(name = "contact_upload_link")
@IdClass(ContactUploadLinkId.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ContactUploadLink {

    @Id
    @Column(name = "upload_id")
    private UUID uploadId;

    @Id
    @Column(name = "contact_id")
    private UUID contactId;
}
