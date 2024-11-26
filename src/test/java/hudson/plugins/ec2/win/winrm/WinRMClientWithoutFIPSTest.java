package hudson.plugins.ec2.win.winrm;

import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;

import java.net.MalformedURLException;
import java.net.URL;

import static org.junit.Assert.fail;

public class WinRMClientWithoutFIPSTest {

    @ClassRule
    public static FlagRule<String>
            fipsSystemPropertyRule = FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "false");

    /**
     * When FIPS is not enabled, it should always be allowed to create the {@link WinRMClient}, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testClientCreation() throws MalformedURLException {
        new WinRMClient(new URL("http://localhost"), "username", "password", true)
                .setUseHTTPS(true);
        new WinRMClient(new URL("https://localhost"), "username", "password", true)
                .setUseHTTPS(true);
        new WinRMClient(new URL("http://localhost"), "username", "password", false)
                .setUseHTTPS(true);
        new WinRMClient(new URL("https://localhost"), "username", "password", false)
                .setUseHTTPS(true);
        new WinRMClient(new URL("http://localhost"), "username", null, false)
                .setUseHTTPS(true);
        new WinRMClient(new URL("https://localhost"), "username", null, false)
                .setUseHTTPS(true);

        new WinRMClient(new URL("http://localhost"), "username", "password", true)
                .setUseHTTPS(false);
        new WinRMClient(new URL("https://localhost"), "username", "password", true)
                .setUseHTTPS(false);
        new WinRMClient(new URL("http://localhost"), "username", "password", false)
                .setUseHTTPS(false);
        new WinRMClient(new URL("https://localhost"), "username", "password", false)
                .setUseHTTPS(false);
        new WinRMClient(new URL("http://localhost"), "username", null, false)
                .setUseHTTPS(false);
        new WinRMClient(new URL("https://localhost"), "username", null, false)
                .setUseHTTPS(false);
    }
}