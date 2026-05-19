package et.com.cog.esms.core.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Account lockout service.
 * Tracks failed login attempts per username and per IP.
 * Reference: SDD §5.3
 */
@Service
@RequiredArgsConstructor
public class LockoutService {

    private final StringRedisTemplate redis;

    private static final String USER_FAIL_PREFIX = "lockout:user:";
    private static final String IP_FAIL_PREFIX   = "lockout:ip:";
    private static final String LOCKED_PREFIX    = "lockout:locked:";

    private static final int    USER_THRESHOLD   = 5;
    private static final int    IP_THRESHOLD     = 20;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    private static final Duration WINDOW           = Duration.ofMinutes(15);

    /**
     * Check if a username is currently locked out.
     */
    public boolean isLocked(String username) {
        return Boolean.TRUE.equals(redis.hasKey(LOCKED_PREFIX + username));
    }

    /**
     * Record a failed login attempt. Returns true if the account is now locked.
     */
    public boolean recordFailure(String username, String ipAddress) {
        // Per-username tracking
        Long userFails = redis.opsForValue().increment(USER_FAIL_PREFIX + username);
        if (userFails != null && userFails == 1) {
            redis.expire(USER_FAIL_PREFIX + username, WINDOW);
        }

        // Per-IP tracking
        Long ipFails = redis.opsForValue().increment(IP_FAIL_PREFIX + ipAddress);
        if (ipFails != null && ipFails == 1) {
            redis.expire(IP_FAIL_PREFIX + ipAddress, WINDOW);
        }

        // Lock the account if threshold reached
        if (userFails != null && userFails >= USER_THRESHOLD) {
            redis.opsForValue().set(LOCKED_PREFIX + username, "1", LOCKOUT_DURATION);
            redis.delete(USER_FAIL_PREFIX + username);
            return true;
        }

        return false;
    }

    /**
     * Check if an IP has too many failed attempts.
     */
    public boolean isIpBlocked(String ipAddress) {
        String count = redis.opsForValue().get(IP_FAIL_PREFIX + ipAddress);
        return count != null && Long.parseLong(count) >= IP_THRESHOLD;
    }

    /**
     * Clear failure counters on successful login.
     */
    public void clearFailures(String username) {
        redis.delete(USER_FAIL_PREFIX + username);
    }
}
