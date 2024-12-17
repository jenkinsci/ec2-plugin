package hudson.plugins.ec2;

import com.amazonaws.services.ec2.model.InstanceType;
import hudson.model.Node;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import java.security.Security;
import java.util.Arrays;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class EC2SlaveMonitorTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Before
    public void init() {
        // Tests using the BouncyCastleProvider failed without that
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    @Test
    public void testMinimumNumberOfInstances() throws Exception {
        SlaveTemplate template = new SlaveTemplate(
                "ami1",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "foo ami",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                2,
                0,
                null,
                null,
                true,
                true,
                "",
                false,
                "",
                false,
                false,
                true,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        AmazonEC2Cloud cloud = new AmazonEC2Cloud(
                "us-east-1",
                true,
                "abc",
                "us-east-1",
                null,
                "ghi",
                "3",
                Collections.singletonList(template),
                "roleArn",
                "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        Assert.assertEquals(
                2,
                Arrays.stream(Jenkins.get().getComputers())
                        .filter(EC2Computer.class::isInstance)
                        .count());
    }

    @Test
    public void testMinimumNumberOfSpareInstances() throws Exception {
        // Arguments split onto newlines matching the construtor definition to make figuring which is which easier.
        SlaveTemplate template = new SlaveTemplate(
                "ami1",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "defaultsecgroup",
                "remotefs",
                InstanceType.M1Large,
                false,
                "label",
                Node.Mode.NORMAL,
                "description",
                "init script",
                "tmpdir",
                "userdata",
                "10",
                "remoteadmin",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                "0",
                0,
                2,
                null,
                null,
                true,
                true,
                "",
                false,
                "",
                false,
                false,
                true,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        AmazonEC2Cloud cloud = new AmazonEC2Cloud(
                "us-east-1",
                true,
                "abc",
                "us-east-1",
                null,
                "ghi",
                "3",
                Collections.singletonList(template),
                "roleArn",
                "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();
        Assert.assertEquals(
                2,
                Arrays.stream(Jenkins.get().getComputers())
                        .filter(EC2Computer.class::isInstance)
                        .count());
    }
}
