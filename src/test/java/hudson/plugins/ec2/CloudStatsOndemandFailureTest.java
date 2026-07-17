package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.Computer;
import hudson.model.Label;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SimpleCommandLauncher;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

/**
 * Asserts the external {@code cloud-stats} behaviour when provisioning fails, so that a {@link ProvisioningActivity}
 * never dangles in a non-terminal phase. Two distinct failure modes are covered at their respective seams:
 *
 * <ul>
 *   <li><b>No agent materialises</b> ({@code AmazonEC2Factory} seam): the async provisioning future completes
 *       normally with no node -- a swallowed API error, no capacity, or a batch of N planned agents where only
 *       M &lt; N instances come up. {@code NodeProvisioner} drops this outcome silently, so the plugin's own
 *       {@code whenComplete} rescue must record a {@code FAIL} and complete the activity.
 *   <li><b>The agent fails to launch</b> ({@code ComputerLauncher} seam): an instance is up but its agent process
 *       never comes online, so core fires {@code onLaunchFailure} and the listener attaches a {@code FAIL}.
 * </ul>
 *
 * <p>A healthy provision is included as a negative control: the rescue must not record a failure when a real node
 * materialises.
 */
@WithJenkins
class CloudStatsOndemandFailureTest {

    private JenkinsRule r;

    @TempDir
    private File remoteRoot;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @AfterEach
    void resetMock() {
        // The factory mock is static and leaks across tests; reset it so tests relying on the default mock (and other
        // test classes) start clean.
        AmazonEC2FactoryMockImpl.mock = null;
    }

    /**
     * A provisioning attempt that yields no instance (here, a swallowed API error) completes the node future normally
     * with no node. {@code NodeProvisioner} fires no {@code CloudProvisioningListener} callback for that outcome, so
     * the plugin's rescue must record a {@code FAIL} and drive the activity to COMPLETED rather than let it dangle.
     */
    @Test
    void provisioningThatYieldsNoAgentRecordsFailAndNeverDangles() throws Exception {
        CountDownLatch releaseProvisioning = gatedRunInstances(invocation -> {
            throw SdkException.builder()
                    .message("simulated insufficient capacity")
                    .build();
        });

        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        Label label = r.jenkins.getLabel(template.getLabelString());
        Collection<PlannedNode> planned = cloud.provision(label, 1);
        assertEquals(1, planned.size(), "exactly one planned agent for one unit of excess workload");
        TrackedPlannedNode tracked = (TrackedPlannedNode) planned.iterator().next();

        ProvisioningActivity activity =
                CloudStatistics.ProvisioningListener.get().onStarted(tracked.getId());
        assertEquals(ProvisioningActivity.Phase.PROVISIONING, activity.getCurrentPhase());

        releaseProvisioning.countDown(); // let provisioning fail now that the activity exists

        assertNull(tracked.future.get(30, TimeUnit.SECONDS), "a swallowed API error yields no node");
        CloudStatsTestSupport.awaitPhase(activity, ProvisioningActivity.Phase.COMPLETED);

        assertEquals(
                ProvisioningActivity.Phase.COMPLETED,
                activity.getCurrentPhase(),
                "a provisioning that yields no agent must not dangle in PROVISIONING");
        assertEquals(
                ProvisioningActivity.Status.FAIL,
                activity.getStatus(),
                "a provisioning that yields no agent must be recorded as FAIL");
        PhaseExecutionAttachment attachment = CloudStatsTestSupport.failAttachment(activity);
        assertNotNull(attachment, "the unfulfilled provisioning must record a FAIL attachment");
        assertFalse(attachment.getTitle().isBlank(), "the failure attachment must carry a human-readable reason");
    }

    /**
     * A batch requesting two planned agents where only one instance materialises records one progressing activity and
     * one {@code FAIL} activity: the list-index join maps the first planned agent to the single returned instance and
     * the second past the end of the list, so only the second is rescued.
     */
    @Test
    void partialFulfilmentProgressesSomeAndFailsTheRest() throws Exception {
        // Return a single instance however many are requested: fewer instances than planned agents.
        CountDownLatch releaseProvisioning = gatedRunInstances(invocation -> {
            RunInstancesRequest request = invocation.getArgument(0);
            List<Tag> tags = request.tagSpecifications().stream()
                    .map(TagSpecification::tags)
                    .flatMap(List::stream)
                    .toList();
            List<Instance> localInstances = new ArrayList<>();
            localInstances.add(Instance.builder()
                    .instanceId("i-partial-0")
                    .instanceType(request.instanceType())
                    .imageId(request.imageId())
                    .tags(tags)
                    .state(InstanceState.builder()
                            .name(InstanceStateName.RUNNING)
                            .build())
                    .launchTime(Instant.now())
                    .build());
            AmazonEC2FactoryMockImpl.instances.addAll(localInstances);
            return RunInstancesResponse.builder().instances(localInstances).build();
        });

        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        Label label = r.jenkins.getLabel(template.getLabelString());
        Collection<PlannedNode> planned = cloud.provision(label, 2 * template.getNumExecutors());
        assertEquals(2, planned.size(), "two planned agents were requested");
        List<TrackedPlannedNode> nodes =
                planned.stream().map(p -> (TrackedPlannedNode) p).toList();

        CloudStatistics.ProvisioningListener.get().onStarted(cloud, label, planned);
        // Capture both live handles before the FAIL one auto-archives and drops out of getActivityFor(Id).
        ProvisioningActivity first =
                CloudStatistics.get().getActivityFor(nodes.get(0).getId());
        ProvisioningActivity second =
                CloudStatistics.get().getActivityFor(nodes.get(1).getId());
        assertNotNull(first);
        assertNotNull(second);

        releaseProvisioning.countDown();

        assertNotNull(nodes.get(0).future.get(30, TimeUnit.SECONDS), "the first planned agent materialises");
        assertNull(nodes.get(1).future.get(30, TimeUnit.SECONDS), "the second planned agent never materialises");

        CloudStatsTestSupport.awaitPhase(second, ProvisioningActivity.Phase.COMPLETED);
        assertEquals(
                ProvisioningActivity.Phase.COMPLETED,
                second.getCurrentPhase(),
                "the unfulfilled planned agent must complete rather than dangle");
        assertEquals(
                ProvisioningActivity.Status.FAIL,
                second.getStatus(),
                "the unfulfilled planned agent must be recorded as FAIL");
        assertNotNull(
                CloudStatsTestSupport.failAttachment(second),
                "the unfulfilled planned agent must record a FAIL attachment");

        // The fulfilled planned agent keeps progressing and carries no failure.
        assertEquals(
                ProvisioningActivity.Status.OK,
                first.getStatus(),
                "the fulfilled planned agent must not be marked as failed");
        assertNull(
                CloudStatsTestSupport.failAttachment(first),
                "the fulfilled planned agent must record no failure attachment");
    }

    /**
     * An instance that comes up but whose agent never launches: core detects the never-connecting agent, fires
     * {@code onLaunchFailure}, and the listener attaches a {@code FAIL} with a human-readable reason to the phase the
     * agent was in (LAUNCHING), completing the activity rather than leaving it dangling. A failed agent process and a
     * controller-to-agent connection that never establishes both reach the listener through this same
     * {@code onLaunchFailure} hook, so exercising it once covers both.
     */
    @Test
    void agentThatFailsToLaunchRecordsFail() throws Exception {
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("testcloud", "testtemplate", "failing-node");
        CloudStatsTestSupport.LocalLaunchSlave slave = new CloudStatsTestSupport.LocalLaunchSlave(
                "failing-node",
                remoteRoot.getAbsolutePath(),
                new SimpleCommandLauncher("this-agent-command-does-not-exist-ec2cloudstats"));
        slave.setCloudStatsId(id);

        ProvisioningActivity activity =
                CloudStatistics.ProvisioningListener.get().onStarted(id);
        assertEquals(ProvisioningActivity.Phase.PROVISIONING, activity.getCurrentPhase());

        r.jenkins.addNode(slave);
        Computer computer = slave.toComputer();
        assertNotNull(computer, "the added agent must have a computer");
        // The bogus launcher can never bring the agent online. Depending on where the launch fails, connect() may
        // complete normally-but-offline or exceptionally; either way core fires onLaunchFailure.
        try {
            computer.connect(false).get(60, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // A launch failure may surface as an ExecutionException; the FAIL attachment asserted below is the signal.
        }

        CloudStatsTestSupport.awaitPhase(activity, ProvisioningActivity.Phase.COMPLETED);
        assertEquals(
                ProvisioningActivity.Phase.COMPLETED,
                activity.getCurrentPhase(),
                "a launch failure must complete the activity rather than leave it dangling in LAUNCHING");
        assertEquals(
                ProvisioningActivity.Status.FAIL, activity.getStatus(), "a launch failure must be recorded as FAIL");
        PhaseExecutionAttachment attachment = CloudStatsTestSupport.failAttachment(activity);
        assertNotNull(attachment, "the launch failure must record a FAIL attachment");
        assertFalse(attachment.getTitle().isBlank(), "the failure attachment must carry a human-readable reason");
    }

    /**
     * Negative control: a healthy provision resolves to a real node, so the {@code whenComplete} rescue -- whose only
     * job is to fail activities that never yield an agent -- must record no failure.
     */
    @Test
    void healthyProvisionRecordsNoFailureAttachment() throws Exception {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        Label label = r.jenkins.getLabel(template.getLabelString());
        Collection<PlannedNode> planned = cloud.provision(label, 1);
        TrackedPlannedNode tracked = (TrackedPlannedNode) planned.iterator().next();
        ProvisioningActivity activity =
                CloudStatistics.ProvisioningListener.get().onStarted(tracked.getId());

        assertNotNull(tracked.future.get(30, TimeUnit.SECONDS), "a healthy provision must resolve to a real node");
        // Give the fire-and-forget rescue time to run; with a real node it must choose not to record a failure.
        Thread.sleep(500);

        assertEquals(
                ProvisioningActivity.Status.OK,
                activity.getStatus(),
                "a healthy provision must not be marked as failed");
        assertNull(
                CloudStatsTestSupport.failAttachment(activity),
                "a healthy provision must record no failure attachment");
    }

    /**
     * Installs a factory mock whose {@code runInstances} blocks until the returned latch is counted down, then runs
     * {@code answer}. Gating the AWS call lets a test register the cloud-stats activity (via {@code onStarted}) before
     * the provisioning future can complete -- mirroring NodeProvisioner, which calls {@code onStarted} synchronously
     * right after {@code provision()} returns.
     */
    private static CountDownLatch gatedRunInstances(Answer<RunInstancesResponse> answer) {
        CountDownLatch release = new CountDownLatch(1);
        Ec2Client client = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
        Mockito.doAnswer(invocation -> {
                    release.await();
                    return answer.answer(invocation);
                })
                .when(client)
                .runInstances(Mockito.any(RunInstancesRequest.class));
        AmazonEC2FactoryMockImpl.mock = client;
        return release;
    }
}
