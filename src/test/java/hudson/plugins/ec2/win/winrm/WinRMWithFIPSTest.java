package hudson.plugins.ec2.win.winrm;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "true")
class WinRMWithFIPSTest {

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test
    void testSelfSignedCertificateNotAllowed() {
        assertThrows(IllegalArgumentException.class, () -> new WinRM("host", "username", "password", true));
    }

    /**
     * Build valid URL, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testBuildCompliantURL() {
        WinRM winRM = new WinRM("host", "username", "password", false);
        winRM.setUseHTTPS(true);
        winRM.buildURL();
    }

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test
    void testSetUseHTTPSWithPasswordLeak() {
        WinRM winRM = new WinRM("host", "username", "password", false);
        assertThrows(IllegalArgumentException.class, () -> winRM.setUseHTTPS(false));
    }

    /**
     * Using HTTP without a password should be allowed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testSetUseHTTPSWithoutPasswordLeak() {
        WinRM winRM = new WinRM("host", "username", null, false);
        winRM.setUseHTTPS(false);
        winRM.buildURL();
    }
}
