package et.com.cog.esms.core.admin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface CarrierPrefixRepository extends JpaRepository<CarrierPrefix, UUID> {}
