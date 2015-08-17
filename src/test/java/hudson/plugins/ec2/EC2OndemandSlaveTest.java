package hudson.plugins.ec2;

import hudson.model.Node;
import org.jvnet.hudson.test.HudsonTestCase;

public class EC2OndemandSlaveTest extends HudsonTestCase {

    public void testSpecifyMode() throws Exception {
        EC2OndemandSlave slaveNormal = new EC2OndemandSlave("instanceId", "description", "remoteFS", 1, "labelString", Node.Mode.NORMAL, "initScript", "tmpDir", "remoteAdmin", "jvmopts", false, "30", "publicDNS", "privateDNS", null, "cloudName", false, false, 0, new UnixData("a", "b"));
        assertEquals(Node.Mode.NORMAL, slaveNormal.getMode());

        EC2OndemandSlave slaveExclusive = new EC2OndemandSlave("instanceId", "description", "remoteFS", 1, "labelString", Node.Mode.EXCLUSIVE, "initScript", "tmpDir", "remoteAdmin", "jvmopts", false, "30", "publicDNS", "privateDNS", null, "cloudName", false, false, 0, new UnixData("a", "b"));
        assertEquals(Node.Mode.EXCLUSIVE, slaveExclusive.getMode());
    }

}
