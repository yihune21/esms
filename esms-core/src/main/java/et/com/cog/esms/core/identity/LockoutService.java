package et.com.cog.esms.core.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;


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

  
    public boolean isLocked(String username) {
        return Boolean.TRUE.equals(redis.hasKey(LOCKED_PREFIX + username));
    }


    public boolean recordFailure(String username, String ipAddress) {
        Long userFails = redis.opsForValue().increment(USER_FAIL_PREFIX + username);
        if (userFails != null && userFails == 1) {
            redis.expire(USER_FAIL_PREFIX + username, WINDOW);
        }

        Long ipFails = redis.opsForValue().increment(IP_FAIL_PREFIX + ipAddress);
        if (ipFails != null && ipFails == 1) {
            redis.expire(IP_FAIL_PREFIX + ipAddress, WINDOW);
        }

        if (userFails != null && userFails >= USER_THRESHOLD) {
            redis.opsForValue().set(LOCKED_PREFIX + username, "1", LOCKOUT_DURATION);
            redis.delete(USER_FAIL_PREFIX + username);
            return true;
        }

        return false;
    }

 
    public boolean isIpBlocked(String ipAddress) {
        String count = redis.opsForValue().get(IP_FAIL_PREFIX + ipAddress);
        return count != null && Long.parseLong(count) >= IP_THRESHOLD;
    }

  
    public void clearFailures(String username) {
        redis.delete(USER_FAIL_PREFIX + username);
    }
}
