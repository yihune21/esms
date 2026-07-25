package et.com.cog.esms.core.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageCarrierRefRepository extends JpaRepository<MessageCarrierRef, String> {
}
