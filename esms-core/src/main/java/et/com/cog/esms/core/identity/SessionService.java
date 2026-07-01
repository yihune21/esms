package et.com.cog.esms.core.identity;

import et.com.cog.esms.core.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class SessionService {

    private final StringRedisTemplate redis;
    private final JwtTokenProvider tokenProvider;

    private static final String SESSION_PREFIX = "session:refresh:";
    private static final String IDLE_PREFIX    = "session:idle:";
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);


    public void createSession(UUID userId, String refreshJti) {
        Duration refreshTtl = tokenProvider.getRefreshTokenTtl();
        redis.opsForValue().set(SESSION_PREFIX + refreshJti, userId.toString(), refreshTtl);
        redis.opsForValue().set(IDLE_PREFIX + userId, Instant.now().toString(), IDLE_TIMEOUT);
    }


    public void touchIdle(UUID userId) {
        redis.opsForValue().set(IDLE_PREFIX + userId, Instant.now().toString(), IDLE_TIMEOUT);
    }


    public boolean isSessionActive(UUID userId) {
        return Boolean.TRUE.equals(redis.hasKey(IDLE_PREFIX + userId));
    }


    public boolean isRefreshTokenValid(String refreshJti) {
        return Boolean.TRUE.equals(redis.hasKey(SESSION_PREFIX + refreshJti));
    }

    public UUID getUserIdFromRefreshToken(String refreshJti) {
        String userId = redis.opsForValue().get(SESSION_PREFIX + refreshJti);
        return userId != null ? UUID.fromString(userId) : null;
    }


    public void revokeRefreshToken(String refreshJti) {
        redis.delete(SESSION_PREFIX + refreshJti);
    }

    public void revokeAllSessions(UUID userId) {
        redis.delete(IDLE_PREFIX + userId);
    }

    public void denyAccessToken(String accessJti, Duration ttlRemaining) {
        redis.opsForValue().set("jwt:deny:" + accessJti, "1", ttlRemaining);
    }
}
