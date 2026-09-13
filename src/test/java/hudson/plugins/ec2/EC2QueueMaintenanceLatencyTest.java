package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import java.security.Security;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;

/**
 * Queue maintenance must never wait on EC2. {@code Queue.maintain()} runs under the Queue lock and
 * calls into this plugin through {@link EC2RetentionStrategy#check} and
 * {@link EC2Cloud#canProvision}, so any EC2 call reached from there stalls the whole controller.
 * Making that path non-blocking is what took queue maintenance from tens of seconds back to
 * milliseconds.
 *
 * <p>Every EC2 call in these tests takes five seconds, so anything that reached one would blow the
 * budget by an order of magnitude rather than merely being slow.
 *
 * @see <a href="https://github.com/jenkinsci/ec2-plugin/pull/2000">ec2-plugin PR 2000</a>
 */
@WithJenkins
class EC2QueueMaintenanceLatencyTest {

    private static final String LABEL = "linux";
    private static final long EC2_CALL_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long BUDGET_MS = TimeUnit.SECONDS.toMillis(2);

    /** Whether the EC2 client is currently answering slowly. Flipped once the setup is done. */
    private static final AtomicBoolean SLOWED = new AtomicBoolean();

    private JenkinsRule r;
    private EC2Cloud cloud;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        r = rule;
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        SLOWED.set(false);
        AmazonEC2FactoryMockImpl.mock = slowableEc2Client();

        HotSpareConfigByLabel rule2 = new HotSpareConfigByLabel(LABEL);
        rule2.setBaseHotSpares(1);
        rule2.setGracePeriodMinutes(5);
        cloud = cloud(rule2, template("first", InstanceType.T2_MICRO), template("second", InstanceType.M1_LARGE));

        // An agent, provisioned while EC2 is still quick to answer.
        cloud.provision(cloud.getTemplates().get(0), 1);
        SLOWED.set(true);

        // A queued build, so maintenance has something to think about. Everything the queue sets
        // off from here on, including a hot spare pass, meets the slowed down EC2.
        FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(Label.get(LABEL));
        project.scheduleBuild2(0);
    }

    @Test
    void testQueueMaintenanceDoesNotWaitOnEc2() {
        for (int pass = 0; pass < 3; pass++) {
            long elapsed = timed(() -> Jenkins.get().getQueue().maintain());
            assertThat(
                    "queue maintenance pass " + pass + " took " + elapsed + "ms, so it reached an EC2 call",
                    elapsed,
                    lessThan(BUDGET_MS));
        }
    }

    /**
     * The retention strategy is called for every agent on that same locked path, so it has to hand
     * its EC2 work to another thread and return.
     */
    @Test
    void testRetentionStrategyCheckDoesNotWaitOnEc2() {
        EC2Computer computer = anEc2Computer();
        long elapsed = timed(() -> new EC2RetentionStrategy("1").check(computer));

        assertThat(
                "the retention check took " + elapsed + "ms, so it waited on EC2 under the Queue lock",
                elapsed,
                lessThan(BUDGET_MS));
    }

    /**
     * Hot spare scaling, including the label rules, provisions from its own executor. The listeners
     * that ask for a check run on the locked path, so they must only schedule it.
     */
    @Test
    void testSchedulingAHotSpareCheckDoesNotWaitOnEc2() {
        long elapsed = timed(MinimumInstanceChecker::scheduleCheck);

        assertThat(
                "scheduling a hot spare check took " + elapsed + "ms, so it ran the check inline",
                elapsed,
                lessThan(BUDGET_MS));
    }

    /**
     * Maintenance asks every cloud whether it could serve a label, once per queued item.
     */
    @Test
    void testCanProvisionDoesNotWaitOnEc2() {
        long elapsed = timed(() -> cloud.canProvision(Label.get(LABEL)));

        assertThat(
                "canProvision took " + elapsed + "ms, so it consulted EC2 rather than the configuration",
                elapsed,
                lessThan(BUDGET_MS));
    }

    /**
     * Control for the four budget assertions above: a call that is meant to reach EC2 does pay the
     * five seconds, so those measurements really would have caught a blocking call.
     */
    @Test
    void testTheSlowedDownEc2CallsAreReachableAtAll() {
        long elapsed = timed(() -> {
            try {
                cloud.provision(cloud.getTemplates().get(0), 1);
            } catch (Exception e) {
                // The stubbed response is empty, which is fine: only the time spent matters.
            }
        });

        assertThat(
                "provisioning did not reach a slowed down EC2 call, so the budgets prove nothing",
                elapsed,
                greaterThanOrEqualTo(EC2_CALL_DELAY_MS));
    }

    private static long timed(Runnable work) {
        long startedAt = System.nanoTime();
        work.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private EC2Computer anEc2Computer() {
        EC2Computer computer = Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(computer, "the setup should have provisioned an agent");
        return computer;
    }

    /**
     * An EC2 client whose calls can be made to take five seconds each, so a blocking one is
     * unmistakable in the measurements.
     *
     * <p>The delay is a switch rather than a later round of stubbing, because a mock cannot be
     * stubbed once other threads can call it: {@code doAnswer(...).when(mock)} parks the answers in
     * the mock's invocation container, and an agent launching or a hot spare pass calling the same
     * client takes them instead of the stubbed method, which surfaces as Mockito complaining about
     * unfinished stubbing. Here everything is stubbed before the client is published, and the tests
     * only flip a boolean afterwards.
     */
    private static Ec2Client slowableEc2Client() {
        Ec2Client real = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
        Ec2Client slowable = Mockito.mock(Ec2Client.class, AdditionalAnswers.delegatesTo(real));
        Mockito.doAnswer(invocation -> {
                    pauseIfSlowed();
                    return real.describeInstances((DescribeInstancesRequest) invocation.getArgument(0));
                })
                .when(slowable)
                .describeInstances(Mockito.any(DescribeInstancesRequest.class));
        Mockito.doAnswer(invocation -> {
                    pauseIfSlowed();
                    return real.describeSpotInstanceRequests(
                            (DescribeSpotInstanceRequestsRequest) invocation.getArgument(0));
                })
                .when(slowable)
                .describeSpotInstanceRequests(Mockito.any(DescribeSpotInstanceRequestsRequest.class));
        Mockito.doAnswer(invocation -> {
                    pauseIfSlowed();
                    return real.runInstances((RunInstancesRequest) invocation.getArgument(0));
                })
                .when(slowable)
                .runInstances(Mockito.any(RunInstancesRequest.class));
        return slowable;
    }

    private static void pauseIfSlowed() throws InterruptedException {
        if (SLOWED.get()) {
            Thread.sleep(EC2_CALL_DELAY_MS);
        }
    }

    private EC2Cloud cloud(HotSpareConfigByLabel rule, SlaveTemplate... templates) throws Exception {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        // Caps well clear of anything these tests provision: a cap reached would let provisioning
        // return without an EC2 call, and the control below needs it to make one.
        EC2Cloud cloud = new EC2Cloud(
                "test-cloud", true, "abc", "us-east-1", null, "ghi", "100", List.of(templates), null, null);
        cloud.setRoundRobinTemplatesByLabel(true);
        cloud.setHotSpareConfigsByLabel(List.of(rule));
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static SlaveTemplate template(String description, InstanceType type) {
        return new SlaveTemplate(
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
                "50",
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
    }
}
