package et.com.cog.esms.core.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(String code);

    List<Role> findByScope(String scope);

    List<Role> findByStatus(String status);
    List<Role> findByUserId(UUID id);
}
