package et.com.cog.esms.core.activeDirectory;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single source of truth for how eSMS reaches Active Directory.
 *
 * There used to be two: a {@code spring.ldap.*} block (which bound nothing —
 * Spring's own LdapProperties has no service-account-dn/service-account-password
 * field, so those keys were silently discarded) and this {@code app.ad.*} block,
 * with different hosts and different base DNs. Only one of them can be right,
 * and neither was being read. Everything now comes from here, and the Spring
 * LDAP starter is deliberately NOT on the classpath so its autoconfiguration
 * cannot quietly reintroduce a second, competing context source.
 */
@Data
@ConfigurationProperties(prefix = "app.ad")
public class ActiveDirectoryProperties {

    /**
     * When false the AD bind is skipped entirely and login falls back to the
     * local password hash. Intended for local development against a laptop with
     * no line of sight to a domain controller.
     */
    private boolean enabled = true;

    /** ldaps://host:636. Plain ldap:// is accepted but sends the password in clear. */
    private String url = "ldaps://NICDCSrv2.nibins.com:636";

    /** Where user search starts. */
    private String baseDn = "DC=nibins,DC=com";

    /**
     * The read-only service account eSMS searches as. AD accepts a full DN, a
     * userPrincipalName (sms.service@nibins.com) or DOMAIN\\sAMAccountName —
     * whichever the domain admins actually provisioned.
     */
    private String serviceDn = "CN=SMS Service,OU=ServiceAccounts,DC=nibins,DC=com";

    /** Never defaulted. Supplied by AD_SERVICE_PASSWORD; see .env. */
    private String servicePassword;

    /** {0} is replaced with the (escaped) username the user typed. */
    private String userSearchFilter = "(&(objectClass=user)(sAMAccountName={0}))";

    /** TCP connect budget, milliseconds. Short: the DC is on the same LAN. */
    private int connectTimeout = 5_000;

    /** Per-operation read budget, milliseconds. */
    private int readTimeout = 5_000;

    /**
     * Fails startup when the credentials never arrived, rather than at the
     * first login attempt.
     *
     * Spring binds @ConfigurationProperties with unresolvable placeholders
     * ignored, so an unset ${AD_SERVICE_PASSWORD} does not raise an error — the
     * field is populated with the literal string "${AD_SERVICE_PASSWORD}" and
     * every AD bind then fails with an opaque LDAP 49. This turns a silent
     * misconfiguration into an immediate, readable one. Same reasoning, and the
     * same shape, as SmppProperties.validateCredentials().
     */
    @PostConstruct
    void validateCredentials() {
        if (!enabled) return;
        requireResolved("AD_URL", "url", url);
        requireResolved("AD_BASE_DN", "base-dn", baseDn);
        requireResolved("AD_SERVICE_DN", "service-dn", serviceDn);
        requireResolved("AD_SERVICE_PASSWORD", "service-password", servicePassword);
        warnIfNotEncrypted();
    }

    /**
     * An LDAP simple bind sends the password as plaintext octets. Over
     * ldap:// that means every staff password, and the service account's,
     * crosses the network in the clear and can be read by anyone who can
     * capture traffic on the segment.
     *
     * This is not hypothetical here: LDAPS on the NIC domain controllers is
     * not currently serving TLS (both NICDCSrv2/10.10.130.22 and 10.10.130.21
     * accept TCP on 636 and 3269 then reset every handshake, which is what a
     * domain controller with no LDAPS certificate installed does). The obvious
     * workaround is to point AD_URL at ldap://…:389, so this makes the cost of
     * that choice impossible to miss in the log rather than a silent downgrade.
     */
    private void warnIfNotEncrypted() {
        if (url.toLowerCase().startsWith("ldaps://")) return;
        LoggerFactory.getLogger(ActiveDirectoryProperties.class).warn(
                "app.ad.url is '{}' — NOT LDAPS. Every login password, including the "
                        + "AD service account's, will cross the network in cleartext. "
                        + "Use ldaps://…:636 once a certificate is installed on the "
                        + "domain controller.", url);
    }

    private static void requireResolved(String envVar, String key, String value) {
        boolean unresolved = value != null && value.startsWith("${") && value.endsWith("}");
        if (value == null || value.isBlank() || unresolved) {
            throw new IllegalStateException(
                    "app.ad." + key + " is not configured — set the " + envVar
                            + " environment variable (see .env). eSMS cannot authenticate "
                            + "anyone against Active Directory without it. Set AD_ENABLED=false "
                            + "to run on local passwords instead.");
        }
    }
}
