package et.com.cog.esms.core.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

    Optional<AuditLog> findFirstByOrderBySeqDesc();

    /**
     * Serialises hash-chain appends across every application instance.
     *
     * A chain can only be appended to by one writer at a time: two writers
     * that both read the same last row will both chain onto it and fork it.
     * A transaction-scoped advisory lock is used rather than a table lock
     * because it blocks only other appenders, and Postgres releases it
     * automatically on commit or rollback, so it cannot be leaked.
     *
     * pg_advisory_xact_lock() returns void, which has no JDBC mapping, so the
     * call is wrapped in a subquery that yields a plain integer. A "::text"
     * cast cannot be used here: Hibernate treats ":" as a bind-parameter
     * prefix and rewrites "::text" into ":text", producing a syntax error.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS chain_lock",
           nativeQuery = true)
    Integer acquireChainLock(@Param("key") long key);

    /** Chain order for verification: seq is the append order, not created_at. */
    List<AuditLog> findAllByOrderBySeqAsc(Pageable pageable);
}
