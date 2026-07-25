package et.com.cog.esms.sender.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the `app.sender.retry` config into an actual backoff schedule.
 *
 * Both `max-attempts` and `backoff-steps` shipped in application.yml but were
 * read by nothing: a send that failed was acknowledged and dropped, so a
 * transient fault (unbound session, timeout, throttling) silently destroyed
 * the message. Each step here gets its own queue whose only job is to hold a
 * message for that long and then dead-letter it back onto sms.send.q.
 */
@Slf4j
@Getter
@Component
public class RetrySchedule {

    /** How many delivery attempts in total, including the first. */
    private final int maxAttempts;

    /** Delay before attempt N+1, in milliseconds. */
    private final List<Long> backoffMillis = new ArrayList<>();

    /** Queue name per step, parallel to {@link #backoffMillis}. */
    private final List<String> queueNames = new ArrayList<>();

    public RetrySchedule(
            @Value("${app.sender.retry.max-attempts:5}") int maxAttempts,
            @Value("${app.sender.retry.backoff-steps:30s,2m,10m,1h}") String backoffSteps) {

        this.maxAttempts = Math.max(1, maxAttempts);

        for (String rawStep : backoffSteps.split(",")) {
            String step = rawStep.trim().toLowerCase();
            if (step.isEmpty()) continue;
            backoffMillis.add(parseDuration(step));
            queueNames.add("sms.retry." + step + ".q");
        }

        if (backoffMillis.isEmpty()) {
            throw new IllegalStateException(
                    "app.sender.retry.backoff-steps produced no usable steps: " + backoffSteps);
        }
        log.info("Retry schedule: maxAttempts={}, steps={}", this.maxAttempts, queueNames);
    }

    /**
     * The queue that holds a message before the given attempt number.
     * Attempts beyond the last step reuse the longest delay.
     *
     * @param attempt the attempt that just failed, 1-based
     */
    public String queueForAttempt(int attempt) {
        int idx = Math.min(Math.max(attempt, 1), queueNames.size()) - 1;
        return queueNames.get(idx);
    }

    public boolean hasAttemptsLeft(int attempt) {
        return attempt < maxAttempts;
    }

    private static long parseDuration(String step) {
        char unit = step.charAt(step.length() - 1);
        long value;
        try {
            value = Long.parseLong(step.substring(0, step.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Malformed backoff step: " + step, e);
        }
        return switch (unit) {
            case 's' -> value * 1_000L;
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            default  -> throw new IllegalStateException(
                    "Backoff step must end in s, m or h: " + step);
        };
    }
}
