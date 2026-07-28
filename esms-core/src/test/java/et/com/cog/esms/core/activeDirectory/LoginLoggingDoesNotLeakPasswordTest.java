package et.com.cog.esms.core.activeDirectory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step-by-step login logging exists so operators can diagnose sign-in
 * failures from the server log. That makes it a standing hazard: the one thing
 * it must never print is the credential being checked. This captures everything
 * the authenticator logs during real success and failure paths and asserts the
 * passwords are absent — so the guarantee survives anyone later "adding just one
 * more detail" to a log line.
 */
class LoginLoggingDoesNotLeakPasswordTest {

    private static final String BASE_DN    = "DC=nibins,DC=com";
    private static final String SERVICE_DN = "CN=SMS Service,OU=Users,DC=nibins,DC=com";
    private static final String SERVICE_PW = "sErvice-Secret-9182";
    private static final String USER_PW    = "uSer-Secret-4471";

    private static InMemoryDirectoryServer server;
    private static ActiveDirectoryAuthenticator authenticator;

    private ListAppender<ILoggingEvent> captured;
    private Logger adLogger;

    @BeforeAll
    static void startDirectory() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.setSchema(null);
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
                "cn: Abebe Kebede", "sn: Kebede",
                "sAMAccountName: abebe.kebede",
                "displayName: Abebe Kebede",
                "userAccountControl: 512",
                "userPassword: " + USER_PW);

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

    @BeforeEach
    void captureLogs() {
        adLogger = (Logger) LoggerFactory.getLogger(ActiveDirectoryAuthenticator.class);
        adLogger.setLevel(Level.TRACE);
        captured = new ListAppender<>();
        captured.start();
        adLogger.addAppender(captured);
    }

    private String loggedOutput() {
        return captured.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("a SUCCESSFUL login logs each step but never the password")
    void successPathLogsStepsWithoutSecrets() {
        AdAuthResult result = authenticator.authenticate("abebe.kebede", USER_PW);
        assertEquals(AdAuthResult.Status.AUTHENTICATED, result.status());

        String output = loggedOutput();

        // The steps an operator needs are present...
        assertTrue(output.contains("1/5"), output);
        assertTrue(output.contains("2/5"), output);
        assertTrue(output.contains("3/5"), output);
        assertTrue(output.contains("5/5"), output);
        assertTrue(output.contains("abebe.kebede"), "the username should be logged");
        assertTrue(output.contains("CN=Abebe Kebede"), "the resolved DN should be logged");

        // ...and neither credential is.
        assertFalse(output.contains(USER_PW), "the user's password leaked into the log:\n" + output);
        assertFalse(output.contains(SERVICE_PW),
                "the service account password leaked into the log:\n" + output);
    }

    @Test
    @DisplayName("a FAILED login logs the rejection but never the attempted password")
    void failurePathLogsRejectionWithoutSecrets() {
        String attempted = "wrong-Password-Attempt-7788";
        AdAuthResult result = authenticator.authenticate("abebe.kebede", attempted);
        assertEquals(AdAuthResult.Status.BAD_CREDENTIALS, result.status());

        String output = loggedOutput();

        assertTrue(output.contains("REJECTED"), output);
        assertFalse(output.contains(attempted),
                "the attempted password leaked into the log:\n" + output);
        assertFalse(output.contains(SERVICE_PW),
                "the service account password leaked into the log:\n" + output);
    }

    @Test
    @DisplayName("an unknown user is logged as not-in-directory, without the password")
    void unknownUserPathLogsWithoutSecrets() {
        String attempted = "another-Secret-1234";
        AdAuthResult result = authenticator.authenticate("nobody.here", attempted);
        assertEquals(AdAuthResult.Status.NOT_IN_DIRECTORY, result.status());

        String output = loggedOutput();

        assertTrue(output.contains("no entry found"), output);
        assertTrue(output.contains("nobody.here"), output);
        assertFalse(output.contains(attempted),
                "the attempted password leaked into the log:\n" + output);
    }
}
