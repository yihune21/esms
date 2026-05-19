package et.com.cog.esms.core.identity;

import et.com.cog.esms.core.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Redis-backed session management.
 * Tracks refresh tokens and idle timeout (5 min sliding window).
 * Reference: SDD §6, LLD §6.1
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final StringRedisTemplate redis;
    private final JwtTokenProvider tokenProvider;

    private static final String SESSION_PREFIX = "session:refresh:";
    private static final String IDLE_PREFIX    = "session:idle:";
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Create a new session: store refresh token JTI + set idle timer.
     */
    public void createSession(UUID userId, String refreshJti) {
        Duration refreshTtl = tokenProvider.getRefreshTokenTtl();
        redis.opsForValue().set(SESSION_PREFIX + refreshJti, userId.toString(), refreshTtl);
        redis.opsForValue().set(IDLE_PREFIX + userId, Instant.now().toString(), IDLE_TIMEOUT);
    }

    /**
     * Touch the idle timer (called on each authenticated request).
     */
    public void touchIdle(UUID userId) {
        redis.opsForValue().set(IDLE_PREFIX + userId, Instant.now().toString(), IDLE_TIMEOUT);
    }

    /**
     * Check if the session is still active (not idle-expired).
     * Returns true if the user has been active within the idle window.
     */
    public boolean isSessionActive(UUID userId) {
        return Boolean.TRUE.equals(redis.hasKey(IDLE_PREFIX + userId));
    }

    /**
     * Validate a refresh token JTI is still registered.
     */
    public boolean isRefreshTokenValid(String refreshJti) {
        return Boolean.TRUE.equals(redis.hasKey(SESSION_PREFIX + refreshJti));
    }

    /**
     * Get the userId associated with a refresh token.
     */
    public UUID getUserIdFromRefreshToken(String refreshJti) {
        String userId = redis.opsForValue().get(SESSION_PREFIX + refreshJti);
        return userId != null ? UUID.fromString(userId) : null;
    }

    /**
     * Revoke a specific refresh token (logout).
     */
    public void revokeRefreshToken(String refreshJti) {
        redis.delete(SESSION_PREFIX + refreshJti);
    }

    /**
     * Revoke all sessions for a user (force logout).
     */
    public void revokeAllSessions(UUID userId) {
        redis.delete(IDLE_PREFIX + userId);
        // Note: In production, scan for all SESSION_PREFIX keys for this user
        // or maintain a per-user set of active refresh JTIs
    }

    /**
     * Add a JWT access token JTI to the denylist (revoke before expiry).
     */
    public void denyAccessToken(String accessJti, Duration ttlRemaining) {
        redis.opsForValue().set("jwt:deny:" + accessJti, "1", ttlRemaining);
    }
}
