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
    private final ContactUploadLinkRepository uploadLinkRepo;

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

        linkToUpload(uploadId, contact.getId());

        if(groupId != null && !memberRepo.existsByGroupIdAndContactId(groupId, contact.getId())){
            memberRepo.save(ContactGroupMember.builder()
                    .groupId(groupId)
                    .contactId(contact.getId())
                    .build());
        }
        return contact;
    }

    // Records this contact as part of this upload without disturbing any
    // earlier upload it also belongs to.
    private void linkToUpload(UUID uploadId, UUID contactId) {
        if (uploadId == null) return;
        if (!uploadLinkRepo.existsByUploadIdAndContactId(uploadId, contactId)) {
            uploadLinkRepo.save(ContactUploadLink.builder()
                    .uploadId(uploadId)
                    .contactId(contactId)
                    .build());
        }
    }

    // A row whose phone number already matches an existing Contact refreshes
    // that contact's current field values (so reminder due-date matching sees
    // today's ExpiryDate, etc.) and LINKS it to this upload — additively. The
    // contact.upload_id column is kept as "most recently seen in" metadata,
    // but recipient resolution now goes through contact_upload_link, so
    // adding this upload's link never detaches the contact from an earlier
    // upload whose campaign/reminder still needs it (the bug that made sent
    // upload-campaigns show zero recipients after a later file was uploaded).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Contact refreshContactRow(
        UUID contactId,
        String name,
        Map<String, String> extra,
        UUID uploadId,
        UUID groupId
    ) {
        Contact contact = contactRepo.findById(contactId)
                .orElseThrow(() -> new IllegalStateException("Contact not found: " + contactId));
        if (name != null && !name.isEmpty()) {
            contact.setName(name);
        }
        contact.setExtra(extra);
        contact.setUploadId(uploadId);
        contact = contactRepo.save(contact);

        linkToUpload(uploadId, contact.getId());

        if (groupId != null && !memberRepo.existsByGroupIdAndContactId(groupId, contact.getId())) {
            memberRepo.save(ContactGroupMember.builder()
                    .groupId(groupId)
                    .contactId(contact.getId())
                    .build());
        }
        return contact;
    }
}
