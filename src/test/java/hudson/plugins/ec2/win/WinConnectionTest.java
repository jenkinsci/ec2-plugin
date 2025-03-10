package hudson.plugins.ec2.win;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import hudson.plugins.ec2.win.winrm.WindowsProcess;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
import org.junit.Test;

public class WinConnectionTest {

    @Test
    public void testExecute() throws Exception {
        assumeThat(System.getProperty("winrm.host"), is(notNullValue()));
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
