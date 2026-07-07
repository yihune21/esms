package et.com.cog.esms.core.contact;

import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class ContactRowSaver {
    
    private final ContactRepository contactRepo;
    private final ContactGroupMemberRepository memberRepo;
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Contact saveContactRow(
        UUID workspaceId,
        String name,
        String phone,
        Map<String, String> extra,
        UUID uploadId,
        UUID groupId
    ){
        Contact contact = Contact.builder()
                .workspaceId(workspaceId)
                .name(name)
                .phoneE164(phone)
                .extra(extra)
                .uploadId(uploadId)
                .optOut(false)
                .status("ACTIVE")
                .build();
        contact = contactRepo.save(contact);

        if(groupId != null && !memberRepo.existsByGroupIdAndContactId(groupId, contact.getId())){
            memberRepo.save(ContactGroupMember.builder()
                    .groupId(groupId)
                    .contactId(contact.getId())
                    .build());
        }
        return contact;
    }


    
}
