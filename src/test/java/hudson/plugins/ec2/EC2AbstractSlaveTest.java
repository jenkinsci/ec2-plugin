package hudson.plugins.ec2;

import hudson.slaves.NodeProperty;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;

public class EC2AbstractSlaveTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    int timeoutInSecs = Integer.MAX_VALUE;

    @Test
    public void testGetLaunchTimeoutInMillisShouldNotOverflow() throws Exception {
        EC2AbstractSlave slave = new EC2AbstractSlave("name", "id", "description", "fs", 1, null, "label", null, null, "init", "tmpDir", new ArrayList<NodeProperty<?>>(), "root", "jvm", false, "idle", null, "cloud", false, false, Integer.MAX_VALUE, new UnixData("remote", "22"), false) {
            @Override
            public void terminate() {
                // To change body of implemented methods use File | Settings |
                // File Templates.
            }

            @Override
            public String getEc2Type() {
                return null; // To change body of implemented methods use File |
                             // Settings | File Templates.
            }
        };

        assertEquals((long) timeoutInSecs * 1000, slave.getLaunchTimeoutInMillis());
    }
}
