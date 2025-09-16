package hudson.plugins.ec2.win.winrm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.MalformedURLException;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "true")
class WinRMClientWithFIPSTest {

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test
    void testSelfSignedCertificateNotAllowed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WinRMClient(new URL("https://localhost"), "username", "password", true));
    }

    /**
     * Using a password without using TLS, an {@link IllegalArgumentException} is expected
     */
    @Test
    void testCreateClientWithPasswordWithoutTLS() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WinRMClient(new URL("http://localhost"), "username", "password", false));
    }

    /**
     * Using a password with TLS, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testCreateClientWithPasswordWithTLS() throws MalformedURLException {
        new WinRMClient(new URL("https://localhost"), "username", "password", false);
    }

    /**
     * If no password is used TLS can have any value, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testCreateClientWithoutPassword() throws MalformedURLException {
        new WinRMClient(new URL("http://localhost"), "username", null, false);
        new WinRMClient(new URL("https://localhost"), "username", null, false);
    }

    /**
     * It should be allowed to enable useHTTPS on any valid {@link WinRMClient}, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testSetUseHTTPSTrue() throws MalformedURLException {
        new WinRMClient(new URL("https://localhost"), "username", "password", false).setUseHTTPS(true);
        new WinRMClient(new URL("http://localhost"), "username", null, false).setUseHTTPS(true);
        new WinRMClient(new URL("https://localhost"), "username", null, false).setUseHTTPS(true);
    }

    /**
     * It should be allowed to disable useHTTPS only when no password is used, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testSetUseHTTPSFalseWithoutPassword() throws MalformedURLException {
        new WinRMClient(new URL("http://localhost"), "username", null, false).setUseHTTPS(false);
        new WinRMClient(new URL("https://localhost"), "username", null, false).setUseHTTPS(false);
    }

    /**
     * It should not be allowed to disable useHTTPS only when a password is used, an {@link IllegalArgumentException} is expected
     */
    @Test
    void testSetUseHTTPSFalseWithPassword() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WinRMClient(new URL("https://localhost"), "username", "password", false).setUseHTTPS(false));
    }

    /**
     * Password leak prevention when setUseHTTPS is not called
     */
    @Test
    void testBuildWinRMClientWithoutTLS() {
        assertThrows(IllegalArgumentException.class, () -> {
            try {
                WinRMClient winRM = new WinRMClient(new URL("https://localhost"), "username", "password", false);
                // do not call winRM.setUseHTTPS(false); to avoid trigger the check
                winRM.openShell();
            } catch (WinRMConnectException e) {
                fail("The client should not attempt to connect");
            }
        });
    }
}
