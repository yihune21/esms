package et.com.cog.esms.core.contact;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactUploadLinkId implements Serializable {
    private UUID uploadId;
    private UUID contactId;
}
