package hudson.plugins.ec2.win.winrm;

import java.net.MalformedURLException;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "false")
class WinRMClientWithoutFIPSTest {

    /**
     * When FIPS is not enabled, it should always be allowed to create the {@link WinRMClient}, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testClientCreation() throws MalformedURLException {
        new WinRMClient(new URL("http://localhost"), "username", "password", true).setUseHTTPS(true);
        new WinRMClient(new URL("https://localhost"), "username", "password", true).setUseHTTPS(true);
        new WinRMClient(new URL("http://localhost"), "username", "password", false).setUseHTTPS(true);
        new WinRMClient(new URL("https://localhost"), "username", "password", false).setUseHTTPS(true);
        new WinRMClient(new URL("http://localhost"), "username", null, false).setUseHTTPS(true);
        new WinRMClient(new URL("https://localhost"), "username", null, false).setUseHTTPS(true);

        new WinRMClient(new URL("http://localhost"), "username", "password", true).setUseHTTPS(false);
        new WinRMClient(new URL("https://localhost"), "username", "password", true).setUseHTTPS(false);
        new WinRMClient(new URL("http://localhost"), "username", "password", false).setUseHTTPS(false);
        new WinRMClient(new URL("https://localhost"), "username", "password", false).setUseHTTPS(false);
        new WinRMClient(new URL("http://localhost"), "username", null, false).setUseHTTPS(false);
        new WinRMClient(new URL("https://localhost"), "username", null, false).setUseHTTPS(false);
    }
}
