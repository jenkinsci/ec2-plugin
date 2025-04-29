package hudson.plugins.ec2.win;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.plugins.ec2.win.winrm.WindowsProcess;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

class WinConnectionTest {

    @Test
    void testExecute() throws Exception {
        assumeTrue(System.getProperty("winrm.host") != null);
        WinConnection connect = new WinConnection(
                System.getProperty("winrm.host"),
                System.getProperty("winrm.username", "Administrator"),
                System.getProperty("winrm.password"),
                true);
        connect.setUseHTTPS(true);
        WindowsProcess process = connect.execute("dir c:\\");
        process.waitFor();
        String cmdResult = IOUtils.toString(process.getStdout(), StandardCharsets.UTF_8);
        assertTrue(cmdResult.contains("Users"));
    }
}
