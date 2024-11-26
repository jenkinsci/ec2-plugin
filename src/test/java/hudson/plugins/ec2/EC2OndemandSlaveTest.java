package hudson.plugins.ec2;

import static org.junit.Assert.assertEquals;

import hudson.model.Node;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class EC2OndemandSlaveTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void testSpecifyMode() throws Exception {
        EC2OndemandSlave slaveNormal = new EC2OndemandSlave(
                "name",
                "instanceId",
                "description",
                "remoteFS",
                1,
                "labelString",
                Node.Mode.NORMAL,
                "initScript",
                "tmpDir",
                Collections.emptyList(),
                "remoteAdmin",
                "jvmopts",
                false,
                "30",
                "publicDNS",
                "privateDNS",
                Collections.emptyList(),
                "cloudName",
                false,
                0,
                new UnixData("a", null, null, "b", null),
                ConnectionStrategy.PRIVATE_IP,
                -1);
        assertEquals(Node.Mode.NORMAL, slaveNormal.getMode());

        EC2OndemandSlave slaveExclusive = new EC2OndemandSlave(
                "name",
                "instanceId",
                "description",
                "remoteFS",
                1,
                "labelString",
                Node.Mode.EXCLUSIVE,
                "initScript",
                "tmpDir",
                Collections.emptyList(),
                "remoteAdmin",
                "jvmopts",
                false,
                "30",
                "publicDNS",
                "privateDNS",
                Collections.emptyList(),
                "cloudName",
                false,
                0,
                new UnixData("a", null, null, "b", null),
                ConnectionStrategy.PRIVATE_IP,
                -1);

        assertEquals(Node.Mode.EXCLUSIVE, slaveExclusive.getMode());
    }
}
