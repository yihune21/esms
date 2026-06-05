package et.com.cog.esms.sender.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMPP 3.4 connection properties for the NIB internal SMSC.
 * All sensitive values (password) must be injected via environment variables.
 * Reference: LLD §11 — NibSmscSmsGateway
 */
@Data
@ConfigurationProperties(prefix = "app.gateway.nib-smsc")
public class SmppProperties {

    /** SMSC host — NIB internal IP */
    private String host = "10.204.181.70";

    /** SMSC port */
    private int port = 5019;

    /** ESME System ID (username) provided by NIB */
    private String systemId = "6039";

    /** ESME password — inject via NIB_SMSC_PASSWORD env var */
    private String password;

    /** Optional system type string (can be empty) */
    private String systemType = "";

    /** Address range — leave empty to accept all destination numbers */
    private String addressRange = "";

    /**
     * Type of Number for source address.
     * 0 = Unknown, 1 = International, 5 = Alphanumeric
     * Use 5 for sender ID like "NIB", 1 for numeric MSISDN.
     */
    private byte sourceAddressTon = 5;

    /**
     * Numeric Plan Indicator for source address.
     * 0 = Unknown for alphanumeric sender IDs.
     */
    private byte sourceAddressNpi = 0;

    /**
     * Destination TON — 1=International for Ethiopian MSISDNs (251xxxxxxxxx)
     */
    private byte destAddressTon = 1;

    /**
     * Destination NPI — 1=ISDN/telephone
     */
    private byte destAddressNpi = 1;

    /**
     * Enquire-link interval in seconds — keeps the TCP session alive.
     * NIB SMSC typically expects a ping every 30–60 s.
     */
    private int enquireLinkTimer = 30;

    /** TCP connect + SMPP bind timeout in milliseconds */
    private long bindTimeoutMs = 10_000;

    /** Delay before attempting reconnect after session loss (milliseconds) */
    private long reconnectDelayMs = 30_000;

    /** Max reconnect attempts before giving up (0 = unlimited) */
    private int maxReconnectAttempts = 0;

    /**
     * Default sender/service-center address (e.g. "NIB").
     * Can be overridden per-message via SendRequest.senderId().
     */
    private String defaultSenderId = "NIB";
}
