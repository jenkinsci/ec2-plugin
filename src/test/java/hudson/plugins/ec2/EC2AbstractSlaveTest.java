package hudson.plugins.ec2;

import hudson.slaves.NodeProperty;
import hudson.model.Node;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.jvnet.hudson.test.JenkinsRule;
import com.amazonaws.services.ec2.model.InstanceType;


import java.util.ArrayList;

import static org.junit.Assert.assertEquals;

public class EC2AbstractSlaveTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    int timeoutInSecs = Integer.MAX_VALUE;

    @Before
    public void setUp() throws Exception {
        AmazonEC2Cloud.setTestMode(true);
    }

    @After
    public void tearDown() throws Exception {
        AmazonEC2Cloud.setTestMode(false);
    }

    @Test
    public void testGetLaunchTimeoutInMillisShouldNotOverflow() throws Exception {

        EC2AbstractSlave slave = new EC2AbstractSlave("name", "id", "description", "fs", 1, null, "label", null, null, "init", "tmpDir", new ArrayList<NodeProperty<?>>(), "root", "jvm", false, "idle", null, "cloud", false, Integer.MAX_VALUE, new UnixData("remote", null, null, "22"), ConnectionStrategy.PRIVATE_IP, -1) {
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

    @Test
    public void testMaxUsesBackwardCompat() throws Exception {
        final String description = "description";
        SlaveTemplate orig = new SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, null, "", true, false, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, "0");
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);
        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);
        EC2AbstractSlave slave = new EC2AbstractSlave("name", "", description, "fs", 1, null, "label", null, null, "init", "tmpDir", new ArrayList<NodeProperty<?>>(), "root", "jvm", false, "idle", null, "ec2-us-east-1", false, Integer.MAX_VALUE, new UnixData("remote", null, null, "22"), ConnectionStrategy.PRIVATE_IP, 0)  {
            @Override
            public void terminate() {
            }

            @Override
            public String getEc2Type() {
                return null;
            }
        };
        assertEquals(-1, slave.maxTotalUses);
    }
}
