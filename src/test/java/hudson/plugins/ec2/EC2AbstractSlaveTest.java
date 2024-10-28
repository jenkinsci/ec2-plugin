package hudson.plugins.ec2;

import static hudson.plugins.ec2.EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED;
import static hudson.plugins.ec2.EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED;
import static hudson.plugins.ec2.EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED;
import static hudson.plugins.ec2.EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT;

import hudson.slaves.NodeProperty;
import hudson.model.Node;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import com.amazonaws.services.ec2.model.InstanceType;

import static org.junit.Assert.assertEquals;

public class EC2AbstractSlaveTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private int timeoutInSecs = Integer.MAX_VALUE;

    @Test
    public void testGetLaunchTimeoutInMillisShouldNotOverflow() throws Exception {
        EC2AbstractSlave slave = new EC2AbstractSlave("name", "id", "description", "fs", 1, null, "label", null, null, "init", "tmpDir", new ArrayList<NodeProperty<?>>(), "root", "java", "jvm", false, "idle", null, "cloud", Integer.MAX_VALUE, new UnixData("remote", null, null, "22", null), ConnectionStrategy.PRIVATE_IP, -1, Tenancy.Default,
            DEFAULT_METADATA_ENDPOINT_ENABLED, DEFAULT_METADATA_TOKENS_REQUIRED, DEFAULT_METADATA_HOPS_LIMIT, DEFAULT_METADATA_SUPPORTED) {

            @Override
            protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
                return;
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
        SlaveTemplate orig = new SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "java", "-Xmx1g", false, "subnet 456", null, null, 1, 1, "", "profile", false, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null, HostKeyVerificationStrategyEnum.CHECK_NEW_HARD, Tenancy.Default, EbsEncryptRootVolume.DEFAULT,
            DEFAULT_METADATA_ENDPOINT_ENABLED, DEFAULT_METADATA_TOKENS_REQUIRED, DEFAULT_METADATA_HOPS_LIMIT, DEFAULT_METADATA_SUPPORTED);
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);
        String cloudName = "us-east-1";
        AmazonEC2Cloud ac = new AmazonEC2Cloud(cloudName, false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);
        EC2AbstractSlave slave = new EC2AbstractSlave("name", "", description, "fs", 1, null, "label", null, null, "init", "tmpDir", new ArrayList<NodeProperty<?>>(), "root", "jvm", false, "idle", null, cloudName, false, Integer.MAX_VALUE, new UnixData("remote", null, null, "22", null), ConnectionStrategy.PRIVATE_IP, 0)  {
            @Override
            protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
                return;
            }

            @Override
            public String getEc2Type() {
                return null;
            }
        };
        assertEquals(-1, slave.maxTotalUses);
    }
}
