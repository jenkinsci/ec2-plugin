package hudson.plugins.ec2;

import static org.junit.Assert.assertEquals;

import hudson.model.Node;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import software.amazon.awssdk.services.ec2.model.InstanceType;

public class EC2AbstractSlaveTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private int timeoutInSecs = Integer.MAX_VALUE;

    @Test
    public void testGetLaunchTimeoutInMillisShouldNotOverflow() throws Exception {
        EC2AbstractSlave slave =
                new EC2AbstractSlave(
                        "name",
                        "id",
                        "description",
                        "fs",
                        1,
                        null,
                        "label",
                        null,
                        null,
                        "init",
                        "tmpDir",
                        new ArrayList<>(),
                        "root",
                        "java",
                        "jvm",
                        false,
                        "idle",
                        null,
                        "cloud",
                        Integer.MAX_VALUE,
                        new UnixData("remote", null, null, "22", null),
                        ConnectionStrategy.PRIVATE_IP,
                        -1,
                        Tenancy.Default,
                        EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                        EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                        EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                        EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                        EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED) {

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
        SlaveTemplate orig = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
                false,
                "ttt",
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "java",
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                1,
                1,
                "",
                "profile",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                null,
                HostKeyVerificationStrategyEnum.CHECK_NEW_HARD,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);
        String cloudName = "us-east-1";
        EC2Cloud ac = new EC2Cloud(cloudName, false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);
        EC2AbstractSlave slave =
                new EC2AbstractSlave(
                        "name",
                        "",
                        description,
                        "fs",
                        1,
                        null,
                        "label",
                        null,
                        null,
                        "init",
                        "tmpDir",
                        new ArrayList<>(),
                        "root",
                        EC2AbstractSlave.DEFAULT_JAVA_PATH,
                        "jvm",
                        false,
                        "idle",
                        null,
                        cloudName,
                        Integer.MAX_VALUE,
                        new UnixData("remote", null, null, "22", null),
                        ConnectionStrategy.PRIVATE_IP,
                        -1,
                        Tenancy.Default,
                        EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                        EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                        EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                        EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                        EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED) {
                    @Override
                    public void terminate() {}

                    @Override
                    public String getEc2Type() {
                        return null;
                    }
                };
        assertEquals(-1, slave.maxTotalUses);
    }
}
