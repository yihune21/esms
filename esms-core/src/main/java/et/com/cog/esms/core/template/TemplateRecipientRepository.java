package et.com.cog.esms.core.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TemplateRecipientRepository extends JpaRepository<TemplateRecipient, UUID> {

    List<TemplateRecipient> findByTemplateId(UUID templateId);

    Optional<TemplateRecipient> findByTemplateIdAndPhoneE164(UUID templateId, String phoneE164);

    boolean existsByTemplateIdAndPhoneE164(UUID templateId, String phoneE164);

    void deleteByTemplateIdAndPhoneE164(UUID templateId, String phoneE164);

    void deleteByTemplateId(UUID templateId);

    long countByTemplateId(UUID templateId);
}
