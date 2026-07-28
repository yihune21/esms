package et.com.cog.esms.core.activeDirectory;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real authenticator against an in-memory LDAP server standing in
 * for NICDCSrv2. Plain LDAP rather than LDAPS — the transport is the JDK's
 * concern, what is under test is the search-then-bind logic, the fallback
 * signalling and the two ways this can be got dangerously wrong: an empty
 * password, and a username crafted to rewrite the search filter.
 */
class ActiveDirectoryAuthenticatorTest {

    private static final String BASE_DN     = "DC=nibins,DC=com";
    private static final String SERVICE_DN  = "CN=SMS Service,OU=ServiceAccounts,DC=nibins,DC=com";
    private static final String SERVICE_PW  = "service-secret";

    private static InMemoryDirectoryServer server;
    private static ActiveDirectoryAuthenticator authenticator;

    @BeforeAll
    static void startDirectory() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.setSchema(null); // AD attributes such as sAMAccountName are not in the default schema
        config.addAdditionalBindCredentials(SERVICE_DN, SERVICE_PW);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0));

        server = new InMemoryDirectoryServer(config);
        server.startListening();

        server.add("dn: " + BASE_DN, "objectClass: top", "objectClass: domain", "dc: nibins");
        server.add("dn: OU=Users," + BASE_DN, "objectClass: top",
                "objectClass: organizationalUnit", "ou: Users");

        server.add("dn: CN=Abebe Kebede,OU=Users," + BASE_DN,
                "objectClass: top", "objectClass: person",
                "objectClass: organizationalPerson", "objectClass: user",
                "cn: Abebe Kebede",
                "sn: Kebede",
                "sAMAccountName: akebede",
                "displayName: Abebe Kebede",
                "mail: abebe.kebede@nibins.com",
                "userAccountControl: 512",          // NORMAL_ACCOUNT
                "userPassword: correct-horse");

        server.add("dn: CN=Former Staff,OU=Users," + BASE_DN,
                "objectClass: top", "objectClass: person",
                "objectClass: organizationalPerson", "objectClass: user",
                "cn: Former Staff",
                "sn: Staff",
                "sAMAccountName: fstaff",
                "displayName: Former Staff",
                "userAccountControl: 514",          // NORMAL_ACCOUNT | ACCOUNTDISABLE
                "userPassword: whatever");

        ActiveDirectoryProperties props = new ActiveDirectoryProperties();
        props.setEnabled(true);
        props.setUrl("ldap://127.0.0.1:" + server.getListenPort());
        props.setBaseDn(BASE_DN);
        props.setServiceDn(SERVICE_DN);
        props.setServicePassword(SERVICE_PW);
        authenticator = new ActiveDirectoryAuthenticator(props);
    }

    @AfterAll
    static void stopDirectory() {
        if (server != null) server.shutDown(true);
    }

    @Test
    @DisplayName("valid credentials authenticate and carry the AD attributes back")
    void authenticatesValidUser() {
        AdAuthResult result = authenticator.authenticate("akebede", "correct-horse");

        assertEquals(AdAuthResult.Status.AUTHENTICATED, result.status());
        assertNotNull(result.user());
        assertEquals("akebede", result.user().samAccountName());
        assertEquals("Abebe Kebede", result.user().displayName());
        assertEquals("abebe.kebede@nibins.com", result.user().email());
        // Absolute DN, not the base-relative name — binding with a relative one
        // would fail for anyone not sitting directly under the base DN.
        assertTrue(result.user().distinguishedName().endsWith(BASE_DN));
    }

    @Test
    @DisplayName("a known account with the wrong password is rejected outright, never falling back to a local hash")
    void rejectsWrongPasswordWithoutFallback() {
        AdAuthResult result = authenticator.authenticate("akebede", "wrong");

        assertEquals(AdAuthResult.Status.BAD_CREDENTIALS, result.status());
        assertTrue(!result.mayFallBackToLocal(),
                "AD is authoritative for accounts it holds — a stale local password must not let them in");
    }

    @Test
    @DisplayName("an account AD does not hold falls back to local authentication")
    void unknownUserMayFallBack() {
        AdAuthResult result = authenticator.authenticate("superadmin", "anything");

        assertEquals(AdAuthResult.Status.NOT_IN_DIRECTORY, result.status());
        assertTrue(result.mayFallBackToLocal());
        assertNull(result.user());
    }

    @Test
    @DisplayName("an account disabled in AD cannot sign in")
    void rejectsDisabledAdAccount() {
        AdAuthResult result = authenticator.authenticate("fstaff", "whatever");

        assertEquals(AdAuthResult.Status.ACCOUNT_DISABLED, result.status());
        assertTrue(!result.mayFallBackToLocal());
    }

    @Test
    @DisplayName("an empty password is refused without touching the directory")
    void refusesEmptyPassword() {
        // An LDAP simple bind with an empty password is an ANONYMOUS bind and
        // succeeds. Were this not caught, an empty password would authenticate
        // every account in the domain.
        assertEquals(AdAuthResult.Status.BAD_CREDENTIALS,
                authenticator.authenticate("akebede", "").status());
    }

    @Test
    @DisplayName("a username crafted as an LDAP filter matches nobody")
    void doesNotAllowFilterInjection() {
        // Concatenated into the filter this would become
        // (&(objectClass=user)(sAMAccountName=*)) and return the first user in
        // the domain. Passed as a search argument it is escaped and matches an
        // account literally named "*".
        AdAuthResult result = authenticator.authenticate("*)(objectClass=*", "correct-horse");

        assertEquals(AdAuthResult.Status.NOT_IN_DIRECTORY, result.status());
    }

    @Test
    @DisplayName("with AD switched off nothing is contacted and login falls back")
    void disabledSkipsDirectory() {
        ActiveDirectoryProperties off = new ActiveDirectoryProperties();
        off.setEnabled(false);
        off.setUrl("ldap://127.0.0.1:1"); // would fail if it were ever dialled

        AdAuthResult result = new ActiveDirectoryAuthenticator(off).authenticate("akebede", "correct-horse");

        assertEquals(AdAuthResult.Status.DISABLED, result.status());
        assertTrue(result.mayFallBackToLocal());
    }
}
