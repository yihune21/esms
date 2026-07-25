package et.com.cog.esms.core.audit;

import et.com.cog.esms.core.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Append-only audit log with a tamper-evident hash chain: each row hashes its
 * own contents together with the previous row's hash, so altering, deleting or
 * reordering any entry breaks every hash after it.
 *
 * Two things previously stopped that from holding.
 *
 * 1. Appends were not serialised. logAsync() ran on an unbounded thread pool,
 *    so two concurrent writers both read the same "last" row and both chained
 *    onto it. The chain forked, and a fork is indistinguishable from a
 *    deletion when verifying. Appends are now serialised by a single-threaded
 *    executor (within an instance) and a Postgres advisory lock (across
 *    instances) held for the duration of the insert transaction.
 *
 * 2. The hash covered only workspace, actor, category, action and prev_hash.
 *    Severity, entity type and id, detail, IP address and the timestamp were
 *    all absent, so any of them could be rewritten without invalidating a
 *    single hash - which is most of what someone covering their tracks would
 *    want to change. Every field is now covered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /**
     * Key for pg_advisory_xact_lock. Arbitrary, but must be stable and unique
     * to this chain; the lock releases automatically when the transaction ends.
     */
    private static final long CHAIN_LOCK_KEY = 7_735_100_001L;

    /**
     * Field separator and null marker for the canonical form. Control
     * characters are used because they cannot occur in any of the values.
     */
    private static final String SEP  = String.valueOf((char) 0x1F);
    private static final String NULL = String.valueOf((char) 0x00);

    private final AuditLogRepository auditLogRepo;

    @Autowired
    @Lazy
    private AuditService self;

    public void log(UUID workspaceId,
                    String category,
                    String severity,
                    String action,
                    String entityType,
                    UUID entityId) {
        UUID actorId = WorkspaceContext.currentUserId();
        String username = null;
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) username = auth.getName();
        } catch (Exception ignored) {
            // No security context (scheduled jobs, startup): an unattributed
            // entry is still worth recording.
        }
        self.logAsync(workspaceId, category, severity, action, entityType, entityId, actorId, username);
    }

    @Async("auditExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAsync(UUID workspaceId,
                         String category,
                         String severity,
                         String action,
                         String entityType,
                         UUID entityId,
                         UUID actorUserId,
                         String actorUsername) {
        try {
            // Serialises chain appends across every application instance. Held
            // until this transaction commits, so reading the previous row and
            // inserting the row that chains onto it are atomic with respect to
            // any other appender.
            auditLogRepo.acquireChainLock(CHAIN_LOCK_KEY);

            String prevHash = auditLogRepo.findFirstByOrderBySeqDesc()
                    .map(AuditLog::getRowHash)
                    .orElse(null);

            AuditLog entry = AuditLog.builder()
                    .workspaceId(workspaceId)
                    .actorUserId(actorUserId)
                    .actorUsername(actorUsername)
                    .category(category)
                    .severity(severity)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    // Set here rather than by @CreationTimestamp so the value
                    // is known before hashing: a timestamp outside the hash
                    // could be rewritten freely.
                    //
                    // Truncated to microseconds to match TIMESTAMPTZ, which
                    // stores no finer. Instant.now() can carry sub-microsecond
                    // digits, and hashing a value the database then rounds
                    // makes every row fail its own verification on read-back.
                    .createdAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    .prevHash(prevHash)
                    .build();

            entry.setRowHash(hash(entry));
            auditLogRepo.save(entry);

        } catch (Exception e) {
            // Never silently swallowed. A missing audit entry is itself a
            // security-relevant event and has to be visible somewhere.
            log.error("AUDIT WRITE FAILED - action={} category={} actor={} workspace={}",
                    action, category, actorUserId, workspaceId, e);
        }
    }

    /**
     * Canonical form fed to the digest. Fields are separated by a unit
     * separator and nulls written as a NUL marker, so no combination of values
     * can produce the same string as a different set of values - plain
     * concatenation let ("ab","c") and ("a","bc") collide.
     */
    static String canonical(AuditLog e) {
        return String.join(SEP,
                nz(e.getPrevHash()),
                nz(e.getWorkspaceId()),
                nz(e.getActorUserId()),
                nz(e.getActorUsername()),
                nz(e.getCategory()),
                nz(e.getSeverity()),
                nz(e.getAction()),
                nz(e.getEntityType()),
                nz(e.getEntityId()),
                nz(e.getDetail()),
                nz(e.getIpAddress()),
                nz(e.getCreatedAt()));
    }

    static String hash(AuditLog entry) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical(entry).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // SHA-256 is mandated by the JVM spec. If it were genuinely
            // missing we must not fall back to a random value that silently
            // breaks the chain, which is what the previous version did.
            throw new IllegalStateException("SHA-256 unavailable - cannot maintain the audit chain", e);
        }
    }

    private static String nz(Object value) {
        return value == null ? NULL : value.toString();
    }
}
