package hudson.plugins.ec2.win.winrm;

import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;

public class WinRMWithoutFIPSTest {

    @ClassRule
    public static FlagRule<String>
            fipsSystemPropertyRule = FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "false");

    /**
     * When FIPS mode is not activated, no FIPS check should be performed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testSelfSignedCertificateNotAllowed() {
        new WinRM("host", "username", "password", true);
    }

    /**
     * When FIPS mode is not activated, no FIPS check should be performed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testBuildCompliantURL() {
        WinRM winRM = new WinRM("host", "username", "password", false);
        winRM.setUseHTTPS(true);
        winRM.buildURL();
    }

    /**
     * When FIPS mode is not activated, no FIPS check should be performed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testSetuseHTTPSWithPasswordLeak() {
        WinRM winRM = new WinRM("host", "username", "password", false);
        winRM.setUseHTTPS(false);
    }

    /**
     * When FIPS mode is not activated, no FIPS check should be performed, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testSetUseHTTPSWithoutPasswordLeak() {
        WinRM winRM = new WinRM("host", "username", null, false);
        winRM.setUseHTTPS(false);
        winRM.buildURL();
    }
}