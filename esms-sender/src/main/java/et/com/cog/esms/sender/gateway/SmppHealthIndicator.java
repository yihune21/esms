package et.com.cog.esms.sender.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the SMPP bind state through /actuator/health.
 *
 * SmsGateway.health() already tracked this but was wired to nothing, so the
 * endpoint reported UP with the session unbound: the container looked healthy
 * while being incapable of sending a single message. Orchestrators route
 * traffic on that signal, so it has to reflect the link.
 */
@Component("smpp")
@RequiredArgsConstructor
public class SmppHealthIndicator implements HealthIndicator {

    private final SmsGateway gateway;

    @Override
    public Health health() {
        SmsGateway.HealthStatus status = gateway.health();
        Health.Builder builder = switch (status) {
            case HEALTHY     -> Health.up();
            // Reconnecting: degraded rather than down, since the backlog will
            // drain once the bind is re-established.
            case DEGRADED    -> Health.status("DEGRADED");
            case UNAVAILABLE -> Health.down();
        };
        return builder
                .withDetail("carrier", gateway.carrier().name())
                .withDetail("smppState", status.name())
                .build();
    }
}
