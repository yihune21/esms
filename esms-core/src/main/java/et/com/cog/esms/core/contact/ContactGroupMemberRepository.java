package et.com.cog.esms.core.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContactGroupMemberRepository extends JpaRepository<ContactGroupMember, ContactGroupMemberId> {

    List<ContactGroupMember> findByGroupId(UUID groupId);

    boolean existsByGroupIdAndContactId(UUID groupId, UUID contactId);

    void deleteByGroupIdAndContactId(UUID groupId, UUID contactId);

    long countByGroupId(UUID groupId);
}
