package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.model.Node;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class EC2OndemandSlaveTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void testSpecifyMode() throws Exception {
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "jvmopts",
                false,
                "30",
                "publicDNS",
                "privateDNS",
                Collections.emptyList(),
                "cloudName",
                0,
                new UnixData("a", null, null, "b", null),
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Tenancy.Default,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "jvmopts",
                false,
                "30",
                "publicDNS",
                "privateDNS",
                Collections.emptyList(),
                "cloudName",
                0,
                new UnixData("a", null, null, "b", null),
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Tenancy.Default,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);

        assertEquals(Node.Mode.EXCLUSIVE, slaveExclusive.getMode());
    }
}
