package et.com.cog.esms.core.contact;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;


@Entity
@Table(name = "contact_group_member")
@IdClass(ContactGroupMemberId.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ContactGroupMember {

    @Id
    @Column(name = "group_id")
    private UUID groupId;

    @Id
    @Column(name = "contact_id")
    private UUID contactId;
}
