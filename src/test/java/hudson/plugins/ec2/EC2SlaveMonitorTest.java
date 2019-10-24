package hudson.plugins.ec2;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import com.amazonaws.services.ec2.model.InstanceType;

import hudson.model.Node;
import hudson.plugins.ec2.util.PrivateKeyHelper;
import jenkins.model.Jenkins;

public class EC2SlaveMonitorTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void testMinimumNumberOfInstances() throws Exception {
        SlaveTemplate template = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, 2, null, null, true, true, false, "", false, "", false, false, true, ConnectionStrategy.PRIVATE_IP, 0);
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", PrivateKeyHelper.generate(), "3", Collections.singletonList(template), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        Assert.assertEquals(2, Arrays.stream(Jenkins.get().getComputers()).filter(computer -> computer instanceof EC2Computer).count());
    }

    @Test
    public void testMinimumNumberOfSpareInstances() throws Exception {
        // Arguments split onto newlines matching the construtor definition to make figuring which is which easier.
        SlaveTemplate template = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "defaultsecgroup", "remotefs",
                                                   InstanceType.M1Large, false, "label", Node.Mode.NORMAL, "description", "init script",
                                                   "tmpdir", "userdata", "10", "remoteadmin", null, "-Xmx1g",
                                                   false, "subnet 456", null, "0", 0,
                                                   2, null, null, true,
                                                   true, false, "", false,
                                                   "", false, false,
                                                   true, ConnectionStrategy.PRIVATE_IP, 0,
                                                   null);
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", PrivateKeyHelper.generate(), "3", Collections.singletonList(template), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();
        Assert.assertEquals(2, Arrays.stream(Jenkins.get().getComputers()).filter(computer -> computer instanceof EC2Computer).count());
    }
}
