package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.io.File;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecution;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Exercises the async on-demand provisioning path and asserts the external {@code cloud-stats} behaviour:
 * a single {@link ProvisioningActivity} per planned agent, advancing PROVISIONING -> LAUNCHING -> OPERATING and
 * completing cleanly when the agent retires. Correlation is asserted by fingerprint -- the activity and the resulting
 * agent must share one, reached through the agent's opaque correlation id -- and the shared slave factory must never
 * mint a correlation id of its own. The core carries no {@code cloud-stats} type; the activity is opened by the
 * optional extension the moment {@link EC2Cloud#provision(Label, int)} runs, so it exists as soon as provision returns.
 *
 * <p>The lifecycle is proven across two seams. The {@code AmazonEC2Factory} mock drives the real async provisioning
 * path, so it proves PROVISIONING and the planned-node/agent correlation match -- but its mock computer reports itself
 * online without a real launch, and these tests never register the node with Jenkins, so no {@code ComputerListener}
 * fires. LAUNCHING, OPERATING and a clean COMPLETED are therefore proven at the {@code ComputerLauncher} seam, where a
 * substituted local launcher brings the agent genuinely online.
 */
@WithJenkins
class CloudStatsAsyncOndemandTest {

    private JenkinsRule r;

    @TempDir
    private File remoteRoot;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    /**
     * Primary seam ({@code AmazonEC2Factory} mock): the async {@code NodeProvisioner} path records exactly one
     * PROVISIONING activity per planned agent, and the resulting agent carries the same fingerprint (no orphans).
     */
    @Test
    void asyncProvisionRecordsProvisioningActivityMatchingResultingAgent() throws Exception {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        Label label = r.jenkins.getLabel(template.getLabelString());
        Collection<PlannedNode> planned = cloud.provision(label, 1);
        assertEquals(1, planned.size(), "exactly one planned agent for one unit of excess workload");

        // The optional extension opens the activity synchronously inside provision(), so it already exists -- no need
        // to stand in for the NodeProvisioner's CloudProvisioningListener callback the way the old typed tests did.
        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertEquals(1, activities.size(), "exactly one activity for one planned agent");
        ProvisioningActivity activity = activities.get(0);
        assertEquals(ProvisioningActivity.Phase.PROVISIONING, activity.getCurrentPhase());

        // The resulting agent must carry the same identity (fingerprint) as the activity: no orphan activity, no
        // orphan agent. The planned node and the agent are correlated by the caller-injected opaque correlation id.
        PlannedNode plannedNode = planned.iterator().next();
        Node node = plannedNode.future.get(30, TimeUnit.SECONDS);
        assertNotNull(node, "the planned agent must resolve to a real node");
        EC2AbstractSlave slave = (EC2AbstractSlave) node;
        assertNotNull(slave.getCloudStatsCorrelationId(), "the resolved agent must carry a cloud-stats correlation id");
        ProvisioningActivity resolved = CloudStatsTestSupport.activityFor(slave);
        assertNotNull(resolved, "the resolved agent's correlation id must resolve to its activity");
        assertEquals(
                activity.getId().getFingerprint(),
                resolved.getId().getFingerprint(),
                "the activity and the resulting agent must share a single fingerprint");
    }

    /**
     * Primary seam: N planned agents yield N activities -- one per planned agent, each with its own identity. Guards
     * the list-index join against sharing or reusing a single correlation id across the agents it provisions.
     */
    @Test
    void eachPlannedAgentGetsItsOwnActivity() {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        Label label = r.jenkins.getLabel(template.getLabelString());
        // Excess workload of two full agents' worth of executors asks the cloud for two planned agents.
        Collection<PlannedNode> planned = cloud.provision(label, 2 * template.getNumExecutors());
        assertEquals(2, planned.size(), "one planned agent per agent's worth of excess workload");

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertEquals(2, activities.size(), "exactly one activity per planned agent");
        long distinctFingerprints = activities.stream()
                .map(a -> a.getId().getFingerprint())
                .distinct()
                .count();
        assertEquals(2, distinctFingerprints, "each planned agent must carry its own distinct cloud-stats identity");
    }

    /**
     * Secondary seam (substituted {@code ComputerLauncher}): a tracked agent brought genuinely online advances
     * through LAUNCHING to OPERATING (OPERATING fires only on {@code onOnline}), and retiring the healthy agent by
     * node removal completes the activity cleanly, with no failure attachment.
     */
    @Test
    void launchingThenOperatingThenCleanCompletedOnRetirement() throws Exception {
        // Open the activity through the real seam so the agent carries the same opaque correlation id the production
        // path would persist, and the optional listeners can rediscover the activity from it.
        String correlationId =
                EC2ProvisioningTracker.get().onProvisioningStarted("testcloud", "testtemplate", "operating-node");
        assertNotNull(correlationId, "the cloud-stats-backed tracker must mint a correlation id");
        CloudStatsTestSupport.LocalLaunchSlave slave = new CloudStatsTestSupport.LocalLaunchSlave(
                "operating-node", remoteRoot.getAbsolutePath(), r.createComputerLauncher(null));
        slave.setCloudStatsCorrelationId(correlationId);

        ProvisioningActivity activity = CloudStatsTestSupport.activityForCorrelationId(correlationId);
        assertNotNull(activity, "opening the activity must make it resolvable by the agent's correlation id");
        assertEquals(ProvisioningActivity.Phase.PROVISIONING, activity.getCurrentPhase());

        r.jenkins.addNode(slave);
        Computer computer = slave.toComputer();
        assertNotNull(computer, "the added agent must have a computer");
        computer.connect(false).get(60, TimeUnit.SECONDS);

        CloudStatsTestSupport.awaitPhase(activity, ProvisioningActivity.Phase.OPERATING);
        assertEquals(
                ProvisioningActivity.Phase.OPERATING,
                activity.getCurrentPhase(),
                "a fully launched agent must reach OPERATING");
        assertNotNull(
                activity.getPhaseExecution(ProvisioningActivity.Phase.LAUNCHING),
                "LAUNCHING must have been recorded on the way to OPERATING");
        assertEquals(
                ProvisioningActivity.Status.OK, activity.getStatus(), "a healthy launch carries no failure status");

        // Healthy retirement: removing the node completes the activity via the optional completion NodeListener.
        r.jenkins.removeNode(slave);
        CloudStatsTestSupport.awaitPhase(activity, ProvisioningActivity.Phase.COMPLETED);
        assertEquals(
                ProvisioningActivity.Phase.COMPLETED,
                activity.getCurrentPhase(),
                "removing a healthy agent must complete its activity");
        assertEquals(
                ProvisioningActivity.Status.OK,
                activity.getStatus(),
                "a clean completion must not be marked as failed");
        for (PhaseExecution execution : activity.getPhaseExecutions().values()) {
            if (execution != null) {
                assertTrue(
                        execution.getAttachments().isEmpty(),
                        "a clean lifecycle must record no attachments, but " + execution.getPhase() + " had "
                                + execution.getAttachments());
            }
        }
    }

    /**
     * Caller-injected-id invariant: the shared slave factory must never mint a {@code cloud-stats} correlation id. Only
     * the provisioning caller injects it at the list-index join, so a slave built directly by the factory is untracked.
     */
    @Test
    void slaveFactoryNeverMintsIdentity() throws Exception {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        CloudStatsTestSupport.registerCloud(r, template);

        List<EC2AbstractSlave> slaves = template.provision(1, EnumSet.of(SlaveTemplate.ProvisionOptions.ALLOW_CREATE));
        assertNotNull(slaves);
        assertFalse(slaves.isEmpty(), "the template must provision an agent");
        assertNull(
                slaves.get(0).getCloudStatsCorrelationId(),
                "the shared slave factory must never mint a cloud-stats correlation id; only the caller injects it");
    }
}
