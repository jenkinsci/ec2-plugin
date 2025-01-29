package hudson.plugins.ec2.win;

import static org.junit.Assert.fail;

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
    @Test(expected = IllegalArgumentException.class)
    public void testSelfSignedCertificateNotAllowed() {
        new WinConnection("", "", "", true);
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
    @Test(expected = IllegalArgumentException.class)
    public void testSetUseHTTPSFalseWithPassword() {
        new WinConnection("", "alice", "0123456790123456789", false).setUseHTTPS(false);
    }

    /**
     * Password leak prevention when setUseHTTPS is not called
     */
    @Test(expected = IllegalArgumentException.class)
    public void testBuildWinRMClientWithoutTLS() {
        WinConnection winConnection = new WinConnection("", "alice", "0123456790123456789", false);
        winConnection.winrm();
        fail("The creation of the WinRMClient should fail");
    }

    /**
     * When using a password less than 14 chars, an {@link IllegalArgumentException} is expected
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSetShortPassword() {
        new WinConnection("", "alice", "0123", false).setUseHTTPS(false);
    }
}
