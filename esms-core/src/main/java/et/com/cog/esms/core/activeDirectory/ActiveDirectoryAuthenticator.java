package et.com.cog.esms.core.activeDirectory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.Hashtable;

/**
 * Authenticates staff against the NIC domain controller over LDAPS.
 *
 * Two binds per sign-in, which is the standard pattern: the read-only service
 * account binds first and searches for the sAMAccountName the user typed, then
 * we re-bind as the DN that search returned using the password the user typed.
 * The second bind is the actual authentication — we never read, compare or
 * store a password hash from AD, and the user's password is held only for the
 * duration of that bind.
 *
 * Deliberately built on plain JNDI rather than Spring LDAP. Putting
 * spring-ldap-core on the classpath activates Spring Boot's LdapAutoConfiguration,
 * which builds a second ContextSource from {@code spring.ldap.*} defaulting to
 * ldap://localhost:389 — reintroducing exactly the duplicate, half-configured
 * LDAP setup this integration replaced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ActiveDirectoryProperties.class)
public class ActiveDirectoryAuthenticator {

    /** userAccountControl bit 2 — the account is disabled in AD. */
    private static final int UAC_ACCOUNT_DISABLED = 0x0002;
    /** userAccountControl bit 24 — the password has expired. */
    private static final int UAC_PASSWORD_EXPIRED = 0x800000;

    private static final String[] WANTED_ATTRIBUTES =
            {"sAMAccountName", "displayName", "cn", "mail", "userAccountControl"};

    private final ActiveDirectoryProperties props;

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /**
     * @param username what the user typed in the login box (a bare
     *                 sAMAccountName — no domain prefix)
     * @param password their AD password; an empty one is rejected without
     *                 touching the directory
     */
    public AdAuthResult authenticate(String username, String password) {
        if (!props.isEnabled()) {
            log.info("  [AD] skipped — app.ad.enabled=false, login will use the local password");
            return AdAuthResult.of(AdAuthResult.Status.DISABLED);
        }
        // A simple bind with an empty password is an ANONYMOUS bind, and AD
        // answers it with success. Without this check an empty password would
        // authenticate every account in the domain.
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            log.warn("  [AD] refused before contacting the directory — blank username or password "
                    + "(an empty password would otherwise be an anonymous bind, which AD accepts)");
            return AdAuthResult.of(AdAuthResult.Status.BAD_CREDENTIALS);
        }

        log.info("  [AD] 1/5 binding as the service account — url={} serviceDn='{}'",
                props.getUrl(), props.getServiceDn());
        DirContext serviceContext = null;
        try {
            long t0 = System.currentTimeMillis();
            serviceContext = bind(props.getServiceDn(), props.getServicePassword());
            log.info("  [AD] 1/5 service bind OK ({}ms)", System.currentTimeMillis() - t0);
        } catch (AuthenticationException e) {
            // Our own service account was rejected — a configuration fault, not
            // a user error, and it would otherwise look like every staff member
            // suddenly had the wrong password.
            log.error("  [AD] 1/5 SERVICE BIND REJECTED by {} for serviceDn='{}'. {} "
                            + "Check AD_SERVICE_DN and AD_SERVICE_PASSWORD — no one can sign in "
                            + "via AD until this is fixed.",
                    props.getUrl(), props.getServiceDn(), explainBindFailure(e.getMessage()));
            return AdAuthResult.of(AdAuthResult.Status.DIRECTORY_UNAVAILABLE);
        } catch (NamingException e) {
            log.error("  [AD] 1/5 CANNOT REACH the domain controller at {} — {}: {}",
                    props.getUrl(), e.getClass().getSimpleName(), e.getMessage());
            return AdAuthResult.of(AdAuthResult.Status.DIRECTORY_UNAVAILABLE);
        }

        try {
            log.info("  [AD] 2/5 searching for the user — base='{}' filter='{}' scope=SUBTREE arg='{}'",
                    props.getBaseDn(), props.getUserSearchFilter(), username);
            long t1 = System.currentTimeMillis();
            SearchResult entry = findUser(serviceContext, username);
            if (entry == null) {
                log.info("  [AD] 2/5 no entry found for '{}' under {} ({}ms) — login may fall back "
                                + "to a local password", username, props.getBaseDn(),
                        System.currentTimeMillis() - t1);
                return AdAuthResult.of(AdAuthResult.Status.NOT_IN_DIRECTORY);
            }

            Attributes attrs = entry.getAttributes();
            int uac = intAttribute(attrs, "userAccountControl");
            // Absolute DN. entry.getName() is relative to the search base, so
            // binding with it would fail for anyone not sitting directly under
            // the base DN.
            String userDn = entry.getNameInNamespace();
            log.info("  [AD] 3/5 entry found ({}ms) — dn='{}' sam='{}' displayName='{}' mail='{}' "
                            + "userAccountControl={}",
                    System.currentTimeMillis() - t1, userDn,
                    stringAttribute(attrs, "sAMAccountName"),
                    stringAttribute(attrs, "displayName"),
                    stringAttribute(attrs, "mail"), uac);

            if ((uac & UAC_ACCOUNT_DISABLED) != 0) {
                log.warn("  [AD] 3/5 REJECTED — account '{}' is disabled in the directory "
                        + "(userAccountControl={} has bit 0x2 set)", username, uac);
                return AdAuthResult.of(AdAuthResult.Status.ACCOUNT_DISABLED);
            }
            if ((uac & UAC_PASSWORD_EXPIRED) != 0) {
                log.warn("  [AD] 3/5 REJECTED — account '{}' has an expired password "
                        + "(userAccountControl={} has bit 0x800000 set)", username, uac);
                return AdAuthResult.of(AdAuthResult.Status.ACCOUNT_DISABLED);
            }

            log.info("  [AD] 4/5 re-binding as the user to verify their password — dn='{}'", userDn);
            DirContext userContext = null;
            try {
                long t2 = System.currentTimeMillis();
                userContext = bind(userDn, password);
                log.info("  [AD] 5/5 user bind OK ({}ms) — credentials verified by the directory",
                        System.currentTimeMillis() - t2);
            } catch (AuthenticationException e) {
                // AD encodes the real reason as "data <hex>" in the message:
                // 52e bad password, 525 no such user, 530/531 time/workstation
                // restriction, 532 password expired, 533 disabled, 701 expired,
                // 773 must change password, 775 locked out.
                log.warn("  [AD] 5/5 REJECTED — the directory refused this user's password. {}",
                        explainBindFailure(e.getMessage()));
                return AdAuthResult.of(AdAuthResult.Status.BAD_CREDENTIALS);
            } catch (NamingException e) {
                log.error("  [AD] 5/5 user bind failed for '{}': {}", username, e.getMessage());
                return AdAuthResult.of(AdAuthResult.Status.DIRECTORY_UNAVAILABLE);
            } finally {
                close(userContext);
            }

            String sam = stringAttribute(attrs, "sAMAccountName");
            String display = firstNonBlank(
                    stringAttribute(attrs, "displayName"),
                    stringAttribute(attrs, "cn"),
                    sam != null ? sam : username);

            return AdAuthResult.authenticated(new AdUser(
                    sam != null ? sam : username,
                    display,
                    stringAttribute(attrs, "mail"),
                    userDn));

        } catch (NamingException e) {
            log.error("AD lookup for '{}' failed: {}", username, e.getMessage());
            return AdAuthResult.of(AdAuthResult.Status.DIRECTORY_UNAVAILABLE);
        } finally {
            close(serviceContext);
        }
    }

    private SearchResult findUser(DirContext context, String username) throws NamingException {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(WANTED_ATTRIBUTES);
        controls.setTimeLimit(props.getReadTimeout());
        controls.setCountLimit(1);

        // The username goes in as a filter ARGUMENT, never concatenated into
        // the filter string: search() escapes {0} itself, so a username like
        // "*)(objectClass=*" is matched literally instead of rewriting the
        // filter and returning somebody else's entry.
        NamingEnumeration<SearchResult> results =
                context.search(props.getBaseDn(), props.getUserSearchFilter(),
                        new Object[]{username}, controls);
        try {
            return results.hasMore() ? results.next() : null;
        } catch (PartialResultException e) {
            // AD emits referrals for subtree searches from the domain root. We
            // ignore rather than chase them, and an exhausted enumeration
            // surfaces as this rather than as hasMore()==false.
            return null;
        } finally {
            try {
                results.close();
            } catch (NamingException ignored) {
                // Nothing useful to do — the search already returned.
            }
        }
    }

    private DirContext bind(String principal, String credentials) throws NamingException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, props.getUrl());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, credentials);
        env.put(Context.REFERRAL, "ignore");
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(props.getConnectTimeout()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(props.getReadTimeout()));
        return new InitialDirContext(env);
    }

    private static void close(DirContext context) {
        if (context == null) return;
        try {
            context.close();
        } catch (NamingException e) {
            log.debug("Closing LDAP context failed: {}", e.getMessage());
        }
    }

    /**
     * Turns Active Directory's opaque bind rejection into something readable.
     *
     * AD reports every simple-bind failure as LDAP 49 and hides the real reason
     * in a "data &lt;hex&gt;" fragment of the message. Worse, this DC returns
     * 52e ("bad password") for a non-existent account too, so the code alone
     * cannot distinguish a wrong password from a wrong username — which is
     * exactly the ambiguity that makes these failures expensive to diagnose.
     * Spelling that out here saves the next person from guessing.
     */
    static String explainBindFailure(String message) {
        if (message == null || !message.contains("data ")) {
            return "The directory gave no reason code.";
        }
        String code = message.substring(message.indexOf("data ") + 5).split("[,\\s]")[0];
        String meaning = switch (code) {
            case "525" -> "no such user";
            case "52e" -> "bad password — NOTE: this DC also returns 52e for an account that "
                    + "does not exist, so verify the sAMAccountName as well as the password";
            case "530" -> "not permitted to log on at this time";
            case "531" -> "not permitted to log on from this workstation";
            case "532" -> "password expired";
            case "533" -> "account disabled";
            case "701" -> "account expired";
            case "773" -> "user must change password at next logon — a service account with this "
                    + "flag set can never bind; clear it in ADUC";
            case "775" -> "account locked out";
            default    -> "unrecognised reason code";
        };
        return "AD reason: data " + code + " = " + meaning + ".";
    }

    private static String stringAttribute(Attributes attrs, String name) throws NamingException {
        Attribute attr = attrs.get(name);
        if (attr == null) return null;
        Object value = attr.get();
        return value != null ? value.toString() : null;
    }

    private static int intAttribute(Attributes attrs, String name) throws NamingException {
        String raw = stringAttribute(attrs, name);
        if (raw == null) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate;
        }
        return null;
    }
}
