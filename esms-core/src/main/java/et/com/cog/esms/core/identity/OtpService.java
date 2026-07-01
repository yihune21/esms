package et.com.cog.esms.core.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;


@Service
@RequiredArgsConstructor
public class OtpService {

    private final StringRedisTemplate redis;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String OTP_KEY_PREFIX       = "otp:code:";
    private static final String OTP_ATTEMPTS_PREFIX  = "otp:attempts:";
    private static final int    OTP_LENGTH           = 6;
    private static final int    MAX_ATTEMPTS         = 3;
    private static final Duration OTP_TTL            = Duration.ofMinutes(5);
    private static final Duration RESEND_COOLDOWN    = Duration.ofSeconds(30);

  
    public String generateAndStore(String userId) {
        String otp = generateNumericOtp(OTP_LENGTH);
        redis.opsForValue().set(OTP_KEY_PREFIX + userId, otp, OTP_TTL);
        redis.opsForValue().set(OTP_ATTEMPTS_PREFIX + userId, "0", OTP_TTL);
        return otp;
    }


    public OtpVerificationResult verify(String userId, String submittedOtp) {
        String storedOtp = redis.opsForValue().get(OTP_KEY_PREFIX + userId);
        if (storedOtp == null) {
            return OtpVerificationResult.EXPIRED;
        }

        String attemptsStr = redis.opsForValue().get(OTP_ATTEMPTS_PREFIX + userId);
        int attempts = attemptsStr != null ? Integer.parseInt(attemptsStr) : 0;
        if (attempts >= MAX_ATTEMPTS) {
            invalidate(userId);
            return OtpVerificationResult.MAX_ATTEMPTS;
        }

        if (storedOtp.equals(submittedOtp)) {
            invalidate(userId);
            return OtpVerificationResult.VALID;
        }

        redis.opsForValue().increment(OTP_ATTEMPTS_PREFIX + userId);
        return OtpVerificationResult.INVALID;
    }

    public void invalidate(String userId) {
        redis.delete(OTP_KEY_PREFIX + userId);
        redis.delete(OTP_ATTEMPTS_PREFIX + userId);
    }

    public boolean canResend(String userId) {
        return !Boolean.TRUE.equals(redis.hasKey("otp:cooldown:" + userId));
    }

    public void markResendCooldown(String userId) {
        redis.opsForValue().set("otp:cooldown:" + userId, "1", RESEND_COOLDOWN);
    }

    private String generateNumericOtp(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    public enum OtpVerificationResult {
        VALID, INVALID, EXPIRED, MAX_ATTEMPTS
    }
}
