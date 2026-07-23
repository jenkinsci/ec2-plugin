package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.plugins.ec2.cloudstats.EC2CloudStatsComputerListener;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Asserts that a controller restart neither loses nor duplicates {@code cloud-stats} provisioning activities.
 *
 * <p>Restart is simulated faithfully at the persistence seam rather than by forking a JVM. A real restart (a) writes
 * each agent to {@code nodes/<name>/config.xml} and, on boot, deserializes a fresh agent instance from it via
 * {@link Jenkins#XSTREAM2}, and (b) re-fires the {@code ComputerListener} launch callbacks as the reloaded agent
 * relaunches. Both steps are reproduced directly here -- driving the optional {@code cloud-stats} listener's
 * extension-point step -- which is deterministic and avoids {@code removeNode}, whose {@code onDeleted} would otherwise
 * complete the activity (which a restart does not do). cloud-stats retains its activities across the simulation exactly
 * as it reloads equivalent, same-fingerprint activities from its own {@code cloud-stats.xml} on a real restart.
 *
 * <p>Two guarantees are covered: a tracked agent keeps its persisted opaque correlation id (a rendered activity
 * fingerprint) and continues its single activity (no duplicate, no exception on relaunch); and an agent with no
 * correlation id -- one provisioned before this feature, or a true orphan re-attached via
 * {@link EC2Cloud#attemptReattachOrphanOrStoppedNodes} -- stays untracked (missing, never dangling). Correlation is
 * matched by fingerprint scan (see {@link CloudStatsTestSupport#activityFor(EC2AbstractSlave)}); the agent names no
 * {@code cloud-stats} type.
 */
@WithJenkins
class CloudStatsRestartReattachTest {

    private JenkinsRule r;

    @TempDir
    private File remoteRoot;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        // A null mock forces a fresh Ec2Client (and an empty instance list) for this test, so instance-cap counting
        // starts from zero and provisioning is not throttled by a previous test's instances.
        AmazonEC2FactoryMockImpl.mock = null;
    }

    /**
     * A tracked agent that was operating before a restart keeps its persisted correlation id and continues its single
     * activity: the agent Jenkins reloads from disk carries the same correlation id and still resolves (by fingerprint)
     * to the one existing activity, which is still OPERATING.
     */
    @Test
    void trackedAgentContinuesItsSingleActivityAcrossRestart() throws Exception {
        EC2AbstractSlave slave = operatingTrackedSlave("restart-node");
        String correlationId = slave.getCloudStatsCorrelationId();
        assertNotNull(correlationId, "the operating agent must carry a correlation id before the simulated restart");
        ProvisioningActivity before = CloudStatsTestSupport.activityForCorrelationId(correlationId);
        assertNotNull(before, "the operating agent must be tracked before the simulated restart");
        assertEquals(1, CloudStatistics.get().getActivities().size(), "exactly one activity before restart");
        int fingerprint = before.getId().getFingerprint();

        // Persist as a restart would, then reload the agent the way Jenkins reloads nodes/<name>/config.xml on boot.
        CloudStatistics.get().save();
        EC2AbstractSlave reloaded = reloadViaXStream(slave);

        assertEquals(
                correlationId,
                reloaded.getCloudStatsCorrelationId(),
                "the persisted correlation id must survive the restart");
        ProvisioningActivity continued = CloudStatsTestSupport.activityFor(reloaded);
        assertNotNull(continued, "the reloaded agent must resolve to its existing activity, not spawn a new one");
        assertEquals(fingerprint, continued.getId().getFingerprint(), "continuation is matched by fingerprint");
        assertEquals(
                ProvisioningActivity.Phase.OPERATING,
                continued.getCurrentPhase(),
                "the single activity continues from where it left off");
        assertEquals(1, CloudStatistics.get().getActivities().size(), "a restart must not duplicate the activity");
    }

    /**
     * The relaunch that follows a restart is idempotent: when the reloaded agent comes back online, the
     * {@code ComputerListener} re-fires {@code preLaunch}/{@code onOnline} on an activity that is already at OPERATING.
     * This must be a no-op -- no duplicate activity, and no {@code IllegalStateException} from re-entering a phase --
     * which is what {@code enterIfNotAlready} guarantees over a bare {@code enter}. This drives the same production
     * listener a real relaunch fires, so it is what genuinely pins AC1's "no duplicate activity".
     */
    @Test
    void relaunchAfterRestartDoesNotDuplicateOrThrow() throws Exception {
        EC2AbstractSlave slave = operatingTrackedSlave("relaunch-node");
        ProvisioningActivity activity = CloudStatsTestSupport.activityFor(slave);
        assertNotNull(activity, "the operating agent must be tracked before the relaunch");
        assertEquals(1, CloudStatistics.get().getActivities().size(), "exactly one activity before relaunch");
        Computer computer = slave.toComputer();
        assertNotNull(computer, "the operating agent must have a computer");

        // Reproduce the single step a restart re-fires: the reloaded agent relaunches, so Jenkins calls the launch
        // callbacks again. Driving the optional listener directly is deterministic and mirrors the async test's use of
        // the extension point. Both callbacks must be safe no-ops on an already-OPERATING activity.
        EC2CloudStatsComputerListener listener = ExtensionList.lookupSingleton(EC2CloudStatsComputerListener.class);
        listener.preLaunch(computer, TaskListener.NULL);
        listener.onOnline(computer, TaskListener.NULL);

        assertEquals(
                1,
                CloudStatistics.get().getActivities().size(),
                "the relaunch after restart must not duplicate the activity");
        assertEquals(
                ProvisioningActivity.Phase.OPERATING,
                activity.getCurrentPhase(),
                "re-firing the launch callbacks must leave the activity at OPERATING");
        assertEquals(
                ProvisioningActivity.Status.OK,
                activity.getStatus(),
                "an idempotent relaunch must not degrade a healthy activity");
        assertNull(CloudStatsTestSupport.failAttachment(activity), "an idempotent relaunch must attach no failure");
    }

    /**
     * An agent provisioned before this feature carries no cloud-stats correlation id, so it is untracked. AC2 requires
     * it to stay untracked across a restart: the reloaded agent still has no correlation id and no phantom activity is
     * minted -- missing, never dangling.
     */
    @Test
    void preFeatureAgentWithoutIdStaysUntrackedAcrossRestart() throws Exception {
        // No setCloudStatsCorrelationId(...) -- models an agent whose config.xml predates this feature.
        CloudStatsTestSupport.LocalLaunchSlave slave = new CloudStatsTestSupport.LocalLaunchSlave(
                "pre-feature-node", remoteRoot.getAbsolutePath(), r.createComputerLauncher(null));
        r.jenkins.addNode(slave);

        assertNull(slave.getCloudStatsCorrelationId(), "a pre-feature agent carries no cloud-stats correlation id");
        assertNull(CloudStatsTestSupport.activityFor(slave), "an untracked agent resolves to no activity");
        assertTrue(CloudStatistics.get().getActivities().isEmpty(), "a pre-feature agent mints no activity");

        EC2AbstractSlave reloaded = reloadViaXStream(slave);
        assertNull(reloaded.getCloudStatsCorrelationId(), "a pre-feature agent is still untracked after a restart");
        assertTrue(
                CloudStatistics.get().getActivities().isEmpty(),
                "a restart must mint no activity for a pre-feature agent");
    }

    /**
     * A true orphan adopted by {@link EC2Cloud#attemptReattachOrphanOrStoppedNodes} carries no cloud-stats correlation
     * id (the shared slave factory never mints one), so it is untracked -- exactly like an agent provisioned before this
     * feature. It stays untracked across a restart: nothing appears in the stats, and nothing dangles.
     */
    @Test
    void reattachedOrphanWithoutIdStaysUntrackedAcrossRestart() throws Exception {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        // Leave a RUNNING instance in the fake EC2 that is not yet a Jenkins node -- a true orphan. Provisioning
        // through the template creates the instance but registers no node for it.
        template.provision(1, EnumSet.of(SlaveTemplate.ProvisionOptions.ALLOW_CREATE));
        assertTrue(ec2Agents().isEmpty(), "the orphan instance must not yet be a Jenkins agent");

        cloud.attemptReattachOrphanOrStoppedNodes(r.jenkins, template, 1);
        List<EC2AbstractSlave> agents = ec2Agents();
        assertEquals(1, agents.size(), "the orphan must have been re-attached as a Jenkins agent");
        EC2AbstractSlave reattached = agents.get(0);
        assertNull(
                reattached.getCloudStatsCorrelationId(), "a re-attached orphan carries no cloud-stats correlation id");
        assertNull(CloudStatsTestSupport.activityFor(reattached), "an untracked agent resolves to no activity");
        assertTrue(CloudStatistics.get().getActivities().isEmpty(), "re-attaching an orphan must mint no activity");

        // Across a restart it stays untracked: the persisted config has no correlation id, so the reloaded agent is
        // still untracked and no phantom activity is created.
        EC2AbstractSlave reloaded = reloadViaXStream(reattached);
        assertNull(reloaded.getCloudStatsCorrelationId(), "a re-attached orphan is still untracked after a restart");
        assertTrue(
                CloudStatistics.get().getActivities().isEmpty(),
                "a restart must mint no activity for an untracked orphan");
    }

    /**
     * Provisions a tracked agent and drives it to OPERATING exactly as a live launch would: open its activity via the
     * {@code cloud-stats}-free tracker seam (persisting the returned correlation id on the agent), register it as a
     * node, and connect its computer so the optional {@code ComputerListener} advances it through LAUNCHING to
     * OPERATING. Returns the operating agent.
     */
    private EC2AbstractSlave operatingTrackedSlave(String name) throws Exception {
        String correlationId = EC2ProvisioningTracker.get().onProvisioningStarted("testcloud", "testtemplate", name);
        assertNotNull(correlationId, "the cloud-stats-backed tracker must mint a correlation id");
        CloudStatsTestSupport.LocalLaunchSlave slave = new CloudStatsTestSupport.LocalLaunchSlave(
                name, remoteRoot.getAbsolutePath(), r.createComputerLauncher(null));
        slave.setCloudStatsCorrelationId(correlationId);
        ProvisioningActivity activity = CloudStatsTestSupport.activityForCorrelationId(correlationId);
        assertNotNull(activity, "opening the activity must make it resolvable by the agent's correlation id");
        r.jenkins.addNode(slave);
        Computer computer = slave.toComputer();
        assertNotNull(computer, "the added agent must have a computer");
        computer.connect(false).get(60, TimeUnit.SECONDS);
        CloudStatsTestSupport.awaitPhase(activity, ProvisioningActivity.Phase.OPERATING);
        assertEquals(
                ProvisioningActivity.Phase.OPERATING,
                activity.getCurrentPhase(),
                "the agent must reach OPERATING before the simulated restart");
        return slave;
    }

    /**
     * Reloads an agent the way Jenkins reloads {@code nodes/<name>/config.xml} on boot: deserializes a fresh instance
     * from its persisted XML via the same XStream core uses for agent config. This carries any persisted correlation id
     * across the simulated restart without deleting the live node.
     */
    private static EC2AbstractSlave reloadViaXStream(EC2AbstractSlave slave) {
        return (EC2AbstractSlave) Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(slave));
    }

    private List<EC2AbstractSlave> ec2Agents() {
        return r.jenkins.getNodes().stream()
                .filter(EC2AbstractSlave.class::isInstance)
                .map(EC2AbstractSlave.class::cast)
                .toList();
    }
}
