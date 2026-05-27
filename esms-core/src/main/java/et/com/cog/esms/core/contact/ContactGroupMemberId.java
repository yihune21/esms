package et.com.cog.esms.core.contact;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactGroupMemberId implements Serializable {
    private UUID groupId;
    private UUID contactId;
}
