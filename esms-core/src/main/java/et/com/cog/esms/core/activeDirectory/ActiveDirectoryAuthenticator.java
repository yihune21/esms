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
            return AdAuthResult.of(AdAuthResult.Status.DISABLED);
        }
        // A simple bind with an empty password is an ANONYMOUS bind, and AD
        // answers it with success. Without this check an empty password would
        // authenticate every account in the domain.
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return AdAuthResult.of(AdAuthResult.Status.BAD_CREDENTIALS);
        }

        DirContext serviceContext = null;
        try {
            serviceContext = bind(props.getServiceDn(), props.getServicePassword());
        } catch (AuthenticationException e) {
            // Our own service account was rejected — a configuration fault, not
            // a user error, and it would otherwise look like every staff member
            // suddenly had the wrong password.
            log.error("AD service account '{}' was rejected by {} — check AD_SERVICE_DN and "
                            + "AD_SERVICE_PASSWORD. No one can sign in via AD until this is fixed.",
                    props.getServiceDn(), props.getUrl());
            return AdAuthResult.of(AdAuthResult.Status.DIRECTORY_UNAVAILABLE);
        } catch (NamingException e) {
            log.error("Could not reach the domain controller at {}: {}", props.getUrl(), e.getMessage());
            return AdAuthResult.of(AdAuthResult.Status.DIRECTORY_UNAVAILABLE);
        }

        try {
            SearchResult entry = findUser(serviceContext, username);
            if (entry == null) {
                log.debug("No AD entry for '{}' under {}", username, props.getBaseDn());
                return AdAuthResult.of(AdAuthResult.Status.NOT_IN_DIRECTORY);
            }

            Attributes attrs = entry.getAttributes();
            int uac = intAttribute(attrs, "userAccountControl");
            if ((uac & UAC_ACCOUNT_DISABLED) != 0) {
                log.warn("AD account '{}' is disabled in the directory", username);
                return AdAuthResult.of(AdAuthResult.Status.ACCOUNT_DISABLED);
            }
            if ((uac & UAC_PASSWORD_EXPIRED) != 0) {
                log.warn("AD account '{}' has an expired password", username);
                return AdAuthResult.of(AdAuthResult.Status.ACCOUNT_DISABLED);
            }

            // Absolute DN. entry.getName() is relative to the search base, so
            // binding with it would fail for anyone not sitting directly under
            // the base DN.
            String userDn = entry.getNameInNamespace();

            DirContext userContext = null;
            try {
                userContext = bind(userDn, password);
            } catch (AuthenticationException e) {
                return AdAuthResult.of(AdAuthResult.Status.BAD_CREDENTIALS);
            } catch (NamingException e) {
                log.error("AD bind for '{}' failed: {}", username, e.getMessage());
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
