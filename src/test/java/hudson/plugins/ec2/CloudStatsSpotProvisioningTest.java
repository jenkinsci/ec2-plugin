package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RequestSpotInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RequestSpotInstancesResponse;
import software.amazon.awssdk.services.ec2.model.SpotInstanceRequest;
import software.amazon.awssdk.services.ec2.model.SpotInstanceState;

/**
 * Exercises the async spot provisioning path and asserts the external {@code cloud-stats} behaviour. Spot agents flow
 * through the same subtype-agnostic list-index join as on-demand agents (a single {@link ProvisioningActivity.Id} is
 * minted per planned agent and injected onto whatever the provisioning future yields), so this class proves the three
 * spot-specific outcomes rather than re-proving the shared lifecycle:
 *
 * <ul>
 *   <li><b>Fulfilled</b>: a spot request that comes up as an {@link EC2SpotSlave} carries the activity's single
 *       fingerprint -- no orphan activity, no orphan agent.
 *   <li><b>Unfulfilled</b> ({@code AmazonEC2Factory} seam): a spot request that is never fulfilled records a
 *       {@code FAIL} and completes rather than dangling. The precise AWS cause is swallowed on the async path (to
 *       preserve {@code NodeProvisioner} retry behaviour), so the reason is a generic-but-spot-specific one that
 *       distinguishes a spot-market failure from an on-demand shortfall.
 *   <li><b>Fallback to on-demand</b>: a spot request that falls back to on-demand (the prior-art
 *       {@code SlaveTemplateTest#provisionSpotFallsBackToOndemandWhenSpotQuotaExceeded} flow) remains a single
 *       activity with one fingerprint -- it is not double-counted as both a spot and an on-demand provision.
 * </ul>
 *
 * <p>The launch phases (LAUNCHING, OPERATING) and clean COMPLETED are proven subtype-agnostically in
 * {@code CloudStatsAsyncOndemandTest}: the listener that drives them resolves any {@link EC2AbstractSlave}, of which
 * {@link EC2SpotSlave} is one, so a spot agent needs no separate proof of them (and cannot reach OPERATING here, as
 * the mock computer reports itself online without a real launch).
 */
@WithJenkins
class CloudStatsSpotProvisioningTest {

    private JenkinsRule r;

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
     * A fulfilled spot request yields an {@link EC2SpotSlave} that carries the same fingerprint as its single
     * PROVISIONING activity: the spot subtype flows through the tracked provisioning path exactly like on-demand.
     */
    @Test
    void fulfilledSpotAgentIsTrackedWithStableFingerprint() throws Exception {
        fulfilledSpotClient("sir-fulfilled", "i-spot-fulfilled");

        SlaveTemplate template = spotTemplate(spotConfig(false));
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        Label label = r.jenkins.getLabel(template.getLabelString());
        Collection<PlannedNode> planned = cloud.provision(label, 1);
        assertEquals(1, planned.size(), "exactly one planned agent for one unit of excess workload");
        TrackedPlannedNode tracked = (TrackedPlannedNode) planned.iterator().next();

        ProvisioningActivity activity =
                CloudStatistics.ProvisioningListener.get().onStarted(tracked.getId());
        assertEquals(ProvisioningActivity.Phase.PROVISIONING, activity.getCurrentPhase());
        assertEquals(
                1, CloudStatistics.get().getActivities().size(), "a single fulfilled spot request is one activity");

        Node node = tracked.future.get(30, TimeUnit.SECONDS);
        assertNotNull(node, "a fulfilled spot request must resolve to a real node");
        EC2SpotSlave slave =
                assertInstanceOf(EC2SpotSlave.class, node, "a fulfilled spot request must yield an EC2SpotSlave");
        assertNotNull(slave.getId(), "the resolved spot agent must carry a cloud-stats identity");
        assertEquals(
                activity.getId().getFingerprint(),
                slave.getId().getFingerprint(),
                "the activity and the resulting spot agent must share a single fingerprint");
        assertEquals(
                tracked.getId().getFingerprint(),
                slave.getId().getFingerprint(),
                "the planned node and the resulting spot agent must share a single fingerprint");
    }

    /**
     * A spot request that is never fulfilled (here, spot quota exceeded with no fallback configured) completes the node
     * future normally with no node -- an outcome {@code NodeProvisioner} drops silently -- so the plugin's rescue must
     * record a {@code FAIL} and complete the activity rather than let it dangle. The recorded reason is spot-specific,
     * so a spot-market failure is distinguishable from a generic on-demand shortfall.
     */
    @Test
    void unfulfilledSpotRequestRecordsSpotFailAndNeverDangles() throws Exception {
        CountDownLatch releaseProvisioning = gatedSpotRequest();

        // no fallback: the unfulfilled spot request is terminal
        SlaveTemplate template = spotTemplate(spotConfig(false));
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        Label label = r.jenkins.getLabel(template.getLabelString());
        Collection<PlannedNode> planned = cloud.provision(label, 1);
        assertEquals(1, planned.size(), "exactly one planned agent for one unit of excess workload");
        TrackedPlannedNode tracked = (TrackedPlannedNode) planned.iterator().next();

        ProvisioningActivity activity =
                CloudStatistics.ProvisioningListener.get().onStarted(tracked.getId());
        assertEquals(ProvisioningActivity.Phase.PROVISIONING, activity.getCurrentPhase());

        releaseProvisioning.countDown(); // let the spot request fail now that the activity exists

        assertNull(tracked.future.get(30, TimeUnit.SECONDS), "an unfulfilled spot request yields no node");
        CloudStatsTestSupport.awaitPhase(activity, ProvisioningActivity.Phase.COMPLETED);

        assertEquals(
                ProvisioningActivity.Phase.COMPLETED,
                activity.getCurrentPhase(),
                "an unfulfilled spot request must not dangle in PROVISIONING");
        assertEquals(
                ProvisioningActivity.Status.FAIL,
                activity.getStatus(),
                "an unfulfilled spot request must be recorded as FAIL");
        PhaseExecutionAttachment attachment = CloudStatsTestSupport.failAttachment(activity);
        assertNotNull(attachment, "the unfulfilled spot request must record a FAIL attachment");
        assertTrue(
                attachment.getTitle().toLowerCase(Locale.ROOT).contains("spot"),
                "the spot failure reason must identify it as a spot-market failure, but was: " + attachment.getTitle());
    }

    /**
     * A spot request that falls back to on-demand (spot quota exceeded, fallback enabled -- the prior-art
     * {@code SlaveTemplateTest#provisionSpotFallsBackToOndemandWhenSpotQuotaExceeded} flow) remains a single
     * {@link ProvisioningActivity} with one fingerprint. The one minted id is injected onto whichever agent the future
     * yields -- here the fallback {@link EC2OndemandSlave} -- so the provision is not double-counted as both a spot and
     * an on-demand activity.
     */
    @Test
    void spotFallbackToOndemandRemainsSingleActivity() throws Exception {
        Ec2Client client = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
        doThrow(maxSpotInstanceCountExceeded())
                .when(client)
                .requestSpotInstances(Mockito.any(RequestSpotInstancesRequest.class));
        AmazonEC2FactoryMockImpl.mock = client;

        // quota-exceeded falls back to on-demand
        SlaveTemplate template = spotTemplate(spotConfig(true));
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        Label label = r.jenkins.getLabel(template.getLabelString());
        Collection<PlannedNode> planned = cloud.provision(label, 1);
        assertEquals(1, planned.size(), "exactly one planned agent for one unit of excess workload");
        TrackedPlannedNode tracked = (TrackedPlannedNode) planned.iterator().next();

        ProvisioningActivity activity =
                CloudStatistics.ProvisioningListener.get().onStarted(tracked.getId());

        Node node = tracked.future.get(30, TimeUnit.SECONDS);
        assertNotNull(node, "a spot request with fallback must resolve to an on-demand node");
        EC2OndemandSlave slave = assertInstanceOf(
                EC2OndemandSlave.class, node, "spot-quota-exceeded with fallback must yield an EC2OndemandSlave");
        assertFalse(node instanceof EC2SpotSlave, "the fallback agent must not remain a spot agent");

        assertEquals(
                1,
                CloudStatistics.get().getActivities().size(),
                "a spot request that falls back to on-demand must remain a single activity, not double-counted");
        assertEquals(
                activity.getId().getFingerprint(),
                slave.getId().getFingerprint(),
                "the single activity and the fallback on-demand agent must share one fingerprint");
        assertEquals(
                tracked.getId().getFingerprint(),
                slave.getId().getFingerprint(),
                "the planned node and the fallback on-demand agent must share one fingerprint");
    }

    /** A bid-price spot configuration (max bid {@code .05}) that falls back to on-demand only if {@code fallback}. */
    private static SpotConfiguration spotConfig(boolean fallback) {
        SpotConfiguration config = new SpotConfiguration(true);
        config.setSpotMaxBidPrice(".05");
        config.setFallbackToOndemand(fallback);
        return config;
    }

    /**
     * A spot {@link SlaveTemplate} built from the same known-good full constructor {@code MockEC2Computer} uses for its
     * on-demand template, differing only in the injected {@code spotConfig}. Its label is {@code "ttt"}.
     */
    private static SlaveTemplate spotTemplate(SpotConfiguration spotConfig) {
        return new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                spotConfig,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
                false,
                "ttt",
                Node.Mode.NORMAL,
                "AMI description",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet-123 subnet-456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
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

    /**
     * Installs a factory mock whose spot request is fulfilled and resolves to a RUNNING instance: {@code
     * requestSpotInstances} returns an open request, {@code describeSpotInstanceRequests} reports it ACTIVE and
     * carrying {@code instanceId}, and that instance is RUNNING in the mock's instance list.
     */
    private static void fulfilledSpotClient(String spotRequestId, String instanceId) {
        Ec2Client client = AmazonEC2FactoryMockImpl.createAmazonEC2Mock(); // resets the instance list
        AmazonEC2FactoryMockImpl.instances.add(Instance.builder()
                .instanceId(instanceId)
                .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                .launchTime(Instant.now())
                .build());
        doReturn(RequestSpotInstancesResponse.builder()
                        .spotInstanceRequests(SpotInstanceRequest.builder()
                                .spotInstanceRequestId(spotRequestId)
                                .state(SpotInstanceState.OPEN)
                                .build())
                        .build())
                .when(client)
                .requestSpotInstances(Mockito.any(RequestSpotInstancesRequest.class));
        doReturn(DescribeSpotInstanceRequestsResponse.builder()
                        .spotInstanceRequests(SpotInstanceRequest.builder()
                                .spotInstanceRequestId(spotRequestId)
                                .instanceId(instanceId)
                                .state(SpotInstanceState.ACTIVE)
                                .spotPrice("0.050000")
                                .build())
                        .build())
                .when(client)
                .describeSpotInstanceRequests(Mockito.any(DescribeSpotInstanceRequestsRequest.class));
        AmazonEC2FactoryMockImpl.mock = client;
    }

    /**
     * Installs a factory mock whose {@code requestSpotInstances} blocks until the returned latch is counted down, then
     * throws a spot-quota-exceeded error. Gating the AWS call lets the test register the cloud-stats activity (via
     * {@code onStarted}) before the provisioning future can complete, mirroring NodeProvisioner.
     */
    private static CountDownLatch gatedSpotRequest() {
        CountDownLatch release = new CountDownLatch(1);
        Ec2Client client = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
        Mockito.doAnswer(invocation -> {
                    release.await();
                    throw maxSpotInstanceCountExceeded();
                })
                .when(client)
                .requestSpotInstances(Mockito.any(RequestSpotInstancesRequest.class));
        AmazonEC2FactoryMockImpl.mock = client;
        return release;
    }

    /** The spot-quota-exceeded error AWS raises from {@code requestSpotInstances}, per the prior-art fallback test. */
    private static Ec2Exception maxSpotInstanceCountExceeded() {
        return (Ec2Exception) Ec2Exception.builder()
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .serviceName("AmazonEC2")
                        .errorCode("MaxSpotInstanceCountExceeded")
                        .build())
                .build();
    }
}
