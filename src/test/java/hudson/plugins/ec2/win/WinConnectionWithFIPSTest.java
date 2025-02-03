package hudson.plugins.ec2.win;

import static org.junit.Assert.assertThrows;

import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;

public class WinConnectionWithFIPSTest {

    @ClassRule
    public static FlagRule<String> fipsSystemPropertyRule =
            FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "true");

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test
    public void testSelfSignedCertificateNotAllowed() {
        assertThrows(IllegalArgumentException.class, () -> new WinConnection("", "", "", true));
    }

    /**
     * Using a password with TLS, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testValidCreation() {
        new WinConnection("", "", "0123456790123456789", false);
    }

    /**
     * It should not be allowed to disable useHTTPS only when a password is used, an {@link IllegalArgumentException} is expected
     */
    @Test
    public void testSetUseHTTPSFalseWithPassword() {
        assertThrows(IllegalArgumentException.class, () -> new WinConnection("", "alice", "0123456790123456789", false)
                .setUseHTTPS(false));
    }

    /**
     * Password leak prevention when setUseHTTPS is not called
     */
    @Test
    public void testBuildWinRMClientWithoutTLS() {
        assertThrows(IllegalArgumentException.class, () -> {
            WinConnection winConnection = new WinConnection("", "alice", "0123456790123456789", false);
            winConnection.winrm();
        });
    }

    /**
     * When using a password less than 14 chars, an {@link IllegalArgumentException} is expected
     */
    @Test
    public void testSetShortPassword() {
        assertThrows(
                IllegalArgumentException.class, () -> new WinConnection("", "alice", "0123", false).setUseHTTPS(false));
    }
}
