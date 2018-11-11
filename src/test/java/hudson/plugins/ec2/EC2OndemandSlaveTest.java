package hudson.plugins.ec2;

import hudson.model.Node;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;

public class EC2OndemandSlaveTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void testSpecifyMode() throws Exception {
        EC2OndemandSlave slaveNormal = new EC2OndemandSlave("instanceId", "description", "remoteFS", 1, "labelString", Node.Mode.NORMAL, "initScript", "tmpDir", "remoteAdmin", "jvmopts", false, "30", "publicDNS", "privateDNS", null, "cloudName", false, false, 0, new UnixData("a", null, null, "b"), "-1");
        assertEquals(Node.Mode.NORMAL, slaveNormal.getMode());

        EC2OndemandSlave slaveExclusive = new EC2OndemandSlave("instanceId", "description", "remoteFS", 1, "labelString", Node.Mode.EXCLUSIVE, "initScript", "tmpDir", "remoteAdmin", "jvmopts", false, "30", "publicDNS", "privateDNS", null, "cloudName", false, false, 0, new UnixData("a", null, null, "b"), "-1");
        assertEquals(Node.Mode.EXCLUSIVE, slaveExclusive.getMode());
    }

}
