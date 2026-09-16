package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EC2ComputerMacAgentLogTest {

    @Test
    void rewritesUnixAgentLineToMacAgent() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream log = new PrintStream(new MacAgentLaunchLog(captured), true, StandardCharsets.UTF_8)) {
            log.println("Remoting version: 3352.v17a_fb_4_b_2773f");
            log.println("Launcher: EC2MacLauncher");
            log.println("Communication Protocol: Standard in/out");
            log.println(MacAgentLaunchLog.UNIX_AGENT_LINE);
            log.println("Agent successfully connected and online");
        }

        String output = captured.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(
                "Remoting version: 3352.v17a_fb_4_b_2773f\n"
                        + "Launcher: EC2MacLauncher\n"
                        + "Communication Protocol: Standard in/out\n"
                        + MacAgentLaunchLog.MAC_AGENT_LINE
                        + "\n"
                        + "Agent successfully connected and online\n",
                output);
    }
}
