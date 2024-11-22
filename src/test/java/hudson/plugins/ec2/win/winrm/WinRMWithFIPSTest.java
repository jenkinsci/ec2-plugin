package hudson.plugins.ec2.win.winrm;

import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;

public class WinRMWithFIPSTest {

    @ClassRule
    public static FlagRule<String>
            fipsSystemPropertyRule = FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "true");

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSelfSignedCertificateNotAllowed() {
        new WinRM("host", "username", "password", true);
    }

    /**
     * Build valid URL, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testBuildCompliantURL() {
        WinRM winRM = new WinRM("host", "username", "password", false);
        winRM.setUseHTTPS(true);
        winRM.buildURL();
    }

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSetUseHTTPSWithPasswordLeak() {
        WinRM winRM = new WinRM("host", "username", "password", false);
        winRM.setUseHTTPS(false);
    }

    /**
     * Using HTTP without a password should be allowed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testSetUseHTTPSWithoutPasswordLeak() {
        WinRM winRM = new WinRM("host", "username", null, false);
        winRM.setUseHTTPS(false);
        winRM.buildURL();
    }
}