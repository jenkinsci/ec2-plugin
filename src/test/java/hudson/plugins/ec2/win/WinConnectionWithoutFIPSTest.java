package hudson.plugins.ec2.win;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "false")
class WinConnectionWithoutFIPSTest {

    /**
     * When FIPS is not enabled, it should always be allowed to create the {@link WinConnection}, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testWinConnectionCreation() {
        new WinConnection("", "", "", true).setUseHTTPS(false);
        new WinConnection("", "", "", true).setUseHTTPS(true);
        new WinConnection("", "alice", "yes", true).setUseHTTPS(false);
        new WinConnection("", "alice", "yes", true).setUseHTTPS(true);

        new WinConnection("", "", "", false).setUseHTTPS(false);
        new WinConnection("", "", "", false).setUseHTTPS(true);
        new WinConnection("", "alice", "yes", false).setUseHTTPS(false);
        new WinConnection("", "alice", "yes", false).setUseHTTPS(true);
    }

    /**
     * When FIPS is not enabled, an {@link IllegalArgumentException} is not expected
     */
    @Test
    void testBuildWinRMClientWithoutTLS() {
        WinConnection winConnection = new WinConnection("", "alice", "yes", false);
        winConnection.winrm();
    }
}
