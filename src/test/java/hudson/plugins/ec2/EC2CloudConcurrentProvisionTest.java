package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import java.security.Security;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import software.amazon.awssdk.services.ec2.model.InstanceType;

/**
 * Instance caps under concurrent provisioning requests. Requests that arrive within the instance
 * count cache window must not each be told there is room for the last instance.
 *
 * @see <a href="https://github.com/jenkinsci/ec2-plugin/issues/2030">ec2-plugin issue 2030</a>
 */
@WithJenkins
class EC2CloudConcurrentProvisionTest {

    private static final String LABEL = "linux";

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        AmazonEC2FactoryMockImpl.mock = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
    }

    @Test
    @Issue("2030")
    void testConcurrentRequestsDoNotExceedATemplateInstanceCap() throws Exception {
        EC2Cloud cloud = cloud("20", template("only", InstanceType.T2_MICRO, 1));

        provisionConcurrently(cloud, 4);

        assertThat("a cap of one instance must hold however many requests arrive at once", instanceCount(), equalTo(1));
    }

    @Test
    @Issue("2030")
    void testConcurrentRequestsDoNotExceedTheCloudWideInstanceCap() throws Exception {
        EC2Cloud cloud =
                cloud("2", template("first", InstanceType.T2_MICRO, 10), template("second", InstanceType.M1_LARGE, 10));

        provisionConcurrently(cloud, 4);

        assertThat("the templates had headroom, but the cloud did not", instanceCount(), equalTo(2));
    }

    /**
     * Fires {@code requests} label requests at once, then waits long enough for anything they
     * launched to appear.
     */
    private static void provisionConcurrently(EC2Cloud cloud, int requests) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(requests);
        for (int i = 0; i < requests; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    cloud.provision(Label.get(LABEL), 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        finished.await(30, TimeUnit.SECONDS);
        Thread.sleep(TimeUnit.SECONDS.toMillis(10));
    }

    private static int instanceCount() {
        return AmazonEC2FactoryMockImpl.instances.size();
    }

    private EC2Cloud cloud(String instanceCap, SlaveTemplate... templates) throws Exception {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        EC2Cloud cloud = new EC2Cloud(
                "test-cloud", true, "abc", "us-east-1", null, "ghi", instanceCap, List.of(templates), null, null);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static SlaveTemplate template(String description, InstanceType type, int instanceCap) {
        SlaveTemplate template = new SlaveTemplate(
                "ami-" + description,
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "/tmp/jenkins",
                type.toString(),
                false,
                LABEL,
                Node.Mode.NORMAL,
                description,
                "",
                "",
                "",
                "1",
                "",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "",
                false,
                null,
                null,
                null,
                0,
                0,
                String.valueOf(instanceCap),
                null,
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        /*
         * The mocked describe-instances ignores filters, so without this a request would find the
         * instance another one launched, treat it as an orphan and reuse it rather than testing the
         * cap accounting.
         */
        template.setAvoidUsingOrphanedNodes(true);
        return template;
    }
}
