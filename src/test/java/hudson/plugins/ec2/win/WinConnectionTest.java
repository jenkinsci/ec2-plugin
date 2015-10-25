package hudson.plugins.ec2.win;

import hudson.plugins.ec2.win.winrm.WindowsProcess;
import org.codehaus.plexus.util.IOUtil;
import org.junit.Test;

import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assume.assumeThat;

public class WinConnectionTest {

    @Test
    public void testExecute() throws Exception {
        assumeThat(System.getProperty("winrm.host"), is(notNullValue()));
        WinConnection connect = new WinConnection(System.getProperty("winrm.host"),
                System.getProperty("winrm.username","Administrator"), System.getProperty("winrm.password"));
        connect.setUseHTTPS(true);
        WindowsProcess process = connect.execute("dir c:\\");
        process.waitFor();
        String cmdResult = IOUtil.toString(process.getStdout());
        assertTrue(cmdResult.contains("Users"));
    }
}