package hudson.plugins.ec2.win;

import hudson.plugins.ec2.win.winrm.WinRMClient;
import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;

import static org.junit.Assert.fail;

public class WinConnectionWithoutFIPSTest {

    @ClassRule
    public static FlagRule<String>
            fipsSystemPropertyRule = FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "false");

    /**
     * When FIPS is not enabled, it should always be allowed to create the {@link WinConnection}, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testWinConnectionCreation() {
        new WinConnection("", "" , "", true)
                .setUseHTTPS(false);
        new WinConnection("", "" , "", true)
                .setUseHTTPS(true);
        new WinConnection("", "alice" , "yes", true)
                .setUseHTTPS(false);
        new WinConnection("", "alice" , "yes", true)
                .setUseHTTPS(true);

        new WinConnection("", "" , "", false)
                .setUseHTTPS(false);
        new WinConnection("", "" , "", false)
                .setUseHTTPS(true);
        new WinConnection("", "alice" , "yes", false)
                .setUseHTTPS(false);
        new WinConnection("", "alice" , "yes", false)
                .setUseHTTPS(true);
    }

    /**
     * When FIPS is not enabled, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testBuildWinRMClientWithoutTLS() {
        WinConnection winConnection = new WinConnection("", "alice", "yes", false);
        winConnection.winrm();
    }
}
