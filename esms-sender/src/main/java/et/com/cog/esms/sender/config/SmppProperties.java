package et.com.cog.esms.sender.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;


@Data
@ConfigurationProperties(prefix = "app.gateway.nib-smsc")
public class SmppProperties {

    /**
     * Fails startup when the SMSC credentials never arrived.
     *
     * This is not paranoia. Spring resolves placeholders during
     * @ConfigurationProperties binding with ignoreUnresolvablePlaceholders
     * enabled, so an unset ${NIB_SMSC_PASSWORD} does NOT raise an error — the
     * field is populated with the literal string "${NIB_SMSC_PASSWORD}" and
     * the sender happily tries to bind to the SMSC with that as its password,
     * failing later with an opaque SMPP error. Checking here turns a silent
     * misconfiguration into an immediate, readable one.
     */
    @PostConstruct
    void validateCredentials() {
        requireResolved("NIB_SMSC_HOST", "host", host);
        requireResolved("NIB_SMSC_SYSTEM_ID", "system-id", systemId);
        requireResolved("NIB_SMSC_PASSWORD", "password", password);
    }

    private static void requireResolved(String envVar, String key, String value) {
        boolean unresolved = value != null && value.startsWith("${") && value.endsWith("}");
        if (value == null || value.isBlank() || unresolved) {
            throw new IllegalStateException(
                    "app.gateway.nib-smsc." + key + " is not configured — set the "
                            + envVar + " environment variable (see .env). The sender cannot "
                            + "bind to the NIB SMSC without it.");
        }
    }

    private String host = "10.204.181.70";

    private int port = 5019;

    private String systemId = "6039";

    private String password;

    private String systemType = "";

    private String addressRange = "";

    private byte sourceAddressTon = 5;

    
    private byte sourceAddressNpi = 0;

  
    private byte destAddressTon = 1;


    private byte destAddressNpi = 1;


    private int enquireLinkTimer = 30;

    private long bindTimeoutMs = 10_000;

    private long reconnectDelayMs = 30_000;

    private int maxReconnectAttempts = 0;


    private String defaultSenderId = "NIB";
}
