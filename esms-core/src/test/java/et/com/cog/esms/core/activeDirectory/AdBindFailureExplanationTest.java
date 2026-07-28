package et.com.cog.esms.core.activeDirectory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diagnostic logging is only worth having if it decodes AD's reason codes
 * correctly — an operator reading "data 52e" learns nothing, which is precisely
 * why the earlier service-account outage took so long to pin down.
 */
class AdBindFailureExplanationTest {

    /** The shape jsmpp-free JNDI actually hands back from Active Directory. */
    private static String adMessage(String dataCode) {
        return "[LDAP: error code 49 - 80090308: LdapErr: DSID-0C0903A9, comment: "
                + "AcceptSecurityContext error, data " + dataCode + ", v2580 ]";
    }

    @Test
    @DisplayName("52e warns that it cannot distinguish a bad password from a missing account")
    void decodesBadPasswordWithTheAmbiguityCaveat() {
        String explained = ActiveDirectoryAuthenticator.explainBindFailure(adMessage("52e"));

        assertTrue(explained.contains("data 52e"), explained);
        assertTrue(explained.contains("bad password"), explained);
        // The caveat is the whole point: this DC returns 52e for a non-existent
        // account too, so telling someone only "bad password" sends them off
        // resetting a password when the username is what's wrong.
        assertTrue(explained.contains("sAMAccountName"), explained);
    }

    @Test
    @DisplayName("773 calls out the ADUC checkbox that silently breaks service accounts")
    void decodesMustChangePassword() {
        String explained = ActiveDirectoryAuthenticator.explainBindFailure(adMessage("773"));

        assertTrue(explained.contains("must change password"), explained);
        assertTrue(explained.contains("ADUC"), explained);
    }

    @Test
    @DisplayName("the disabled/locked/expired codes are distinguished from each other")
    void decodesAccountStateCodes() {
        assertTrue(ActiveDirectoryAuthenticator.explainBindFailure(adMessage("533"))
                .contains("account disabled"));
        assertTrue(ActiveDirectoryAuthenticator.explainBindFailure(adMessage("775"))
                .contains("locked out"));
        assertTrue(ActiveDirectoryAuthenticator.explainBindFailure(adMessage("532"))
                .contains("password expired"));
        assertTrue(ActiveDirectoryAuthenticator.explainBindFailure(adMessage("525"))
                .contains("no such user"));
    }

    @Test
    @DisplayName("an unparseable message degrades gracefully instead of throwing")
    void handlesMessagesWithoutAReasonCode() {
        assertTrue(ActiveDirectoryAuthenticator.explainBindFailure(null)
                .contains("no reason code"));
        assertTrue(ActiveDirectoryAuthenticator.explainBindFailure("connection reset")
                .contains("no reason code"));
        assertTrue(ActiveDirectoryAuthenticator.explainBindFailure(adMessage("9999"))
                .contains("unrecognised"));
    }
}
