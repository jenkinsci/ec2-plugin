package hudson.plugins.ec2.win.winrm;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "false")
class WinRMWithoutFIPSTest {

    /**
     * When FIPS mode is not activated, no FIPS check should be performed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testSelfSignedCertificateNotAllowed() {
        new WinRM("host", "username", "password", true);
    }

    /**
     * When FIPS mode is not activated, no FIPS check should be performed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testBuildCompliantURL() {
        WinRM winRM = new WinRM("host", "username", "password", false);
        winRM.setUseHTTPS(true);
        winRM.buildURL();
    }

    /**
     * When FIPS mode is not activated, no FIPS check should be performed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testSetuseHTTPSWithPasswordLeak() {
        WinRM winRM = new WinRM("host", "username", "password", false);
        winRM.setUseHTTPS(false);
    }

    /**
     * When FIPS mode is not activated, no FIPS check should be performed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testSetUseHTTPSWithoutPasswordLeak() {
        WinRM winRM = new WinRM("host", "username", null, false);
        winRM.setUseHTTPS(false);
        winRM.buildURL();
    }
}
