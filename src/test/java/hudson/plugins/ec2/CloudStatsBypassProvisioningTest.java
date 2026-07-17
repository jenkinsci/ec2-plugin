package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecution;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Exercises the three provisioning paths that bypass the async {@code NodeProvisioner} and asserts the external
 * {@code cloud-stats} behaviour: each records exactly one {@link ProvisioningActivity} per agent it provisions.
 *
 * <ul>
 *   <li>the synchronous {@link EC2Cloud#provision(SlaveTemplate, int)} the min-instances / spare-capacity checker
 *       drives (via {@code MinimumInstanceChecker}),
 *   <li>the UI/CLI "Provision" button ({@code doProvision}),
 *   <li>the {@code ec2} pipeline step ({@code EC2Step}).
 * </ul>
 *
 * <p>The first two funnel through the {@code getNewOrExistingAvailableSlave} choke point and then register the agent
 * as a Jenkins node, so the existing {@code ComputerListener}/completion machinery carries them onward. The pipeline
 * step never registers a node, so it must complete its own activity rather than dangle. A final test pins the
 * call-graph-separation invariant: an async provision and a bypass provision are counted independently, so no agent
 * is double counted -- with no runtime guard flag mediating the two paths.
 */
@WithJenkins
class CloudStatsBypassProvisioningTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        // A null mock forces a fresh Ec2Client (and an empty instance list) to be lazily built for this test, so
        // instance-cap counting starts from zero and provisioning is not throttled by a previous test's instances.
        AmazonEC2FactoryMockImpl.mock = null;
    }

    /**
     * The synchronous provisioning path the min-instances / spare-capacity checker uses
     * ({@link EC2Cloud#provision(SlaveTemplate, int)}, called from {@code MinimumInstanceChecker}) records exactly one
     * activity per agent it provisions, and each provisioned agent carries the matching activity's identity.
     */
    @Test
    void minInstanceSyncProvisionRecordsOneActivityPerAgent() {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        cloud.provision(template, 2);

        List<EC2AbstractSlave> agents = ec2Agents();
        assertEquals(2, agents.size(), "two agents must have been provisioned and registered");

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertEquals(2, activities.size(), "exactly one cloud-stats activity per provisioned agent");

        Set<Integer> activityFingerprints = fingerprints(activities);
        assertEquals(2, activityFingerprints.size(), "each agent must carry its own distinct activity identity");
        for (EC2AbstractSlave agent : agents) {
            assertNotNull(agent.getId(), "each provisioned agent must carry a cloud-stats identity");
            assertTrue(
                    activityFingerprints.contains(agent.getId().getFingerprint()),
                    "each provisioned agent must be tracked by exactly one activity sharing its fingerprint");
        }
    }

    /**
     * The UI/CLI "Provision" button ({@code doProvision}) records exactly one activity for the single agent it
     * provisions, and that agent carries the matching activity's identity.
     */
    @Test
    void doProvisionButtonRecordsOneActivityPerAgent() throws Exception {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);

        cloud.doProvision(template.description);

        List<EC2AbstractSlave> agents = ec2Agents();
        assertEquals(1, agents.size(), "the provision button provisions a single agent");

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertEquals(1, activities.size(), "exactly one cloud-stats activity for the provisioned agent");
        assertNotNull(agents.get(0).getId(), "the provisioned agent must carry a cloud-stats identity");
        assertEquals(
                activities.get(0).getId().getFingerprint(),
                agents.get(0).getId().getFingerprint(),
                "the activity and the provisioned agent must share a single fingerprint");
    }

    /**
     * The {@code ec2} pipeline step records exactly one activity for the instance it provisions. Because the step
     * hands the raw instance to the pipeline without ever registering a Jenkins node, it must complete its own
     * activity (rather than leave it dangling), and a healthy provision must not attach a premature-completion
     * warning.
     */
    @Test
    void ec2StepRecordsOneCompletedActivityWithoutWarning() throws Exception {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        CloudStatsTestSupport.registerCloud(r, template);

        WorkflowJob job = r.createProject(WorkflowJob.class);
        String builtInNodeLabel = r.jenkins.getSelfLabel().getName();
        job.setDefinition(new CpsFlowDefinition(
                "node('" + builtInNodeLabel + "') {\n"
                        + "  ec2 cloud: 'testcloud', template: '" + template.description + "'\n"
                        + "}",
                true));
        r.buildAndAssertSuccess(job);

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertEquals(1, activities.size(), "the ec2 step records exactly one activity for the instance it provisions");

        ProvisioningActivity activity = activities.get(0);
        assertEquals(
                ProvisioningActivity.Phase.COMPLETED,
                activity.getCurrentPhase(),
                "the ec2 step never registers a Jenkins node, so it must complete its own activity rather than dangle");
        assertEquals(
                ProvisioningActivity.Status.OK,
                activity.getStatus(),
                "a successful instance provision is not a failure");
        for (PhaseExecution execution : activity.getPhaseExecutions().values()) {
            if (execution == null) {
                continue; // phases the step legitimately skips (e.g. LAUNCHING) map to a null execution
            }
            assertTrue(
                    execution.getAttachments().isEmpty(),
                    "a healthy ec2-step provision must attach nothing (no premature-completion warning), but "
                            + execution.getPhase() + " had " + execution.getAttachments());
        }
    }

    /**
     * Call-graph-separation invariant: an async {@code NodeProvisioner} provision and a bypass provision are counted
     * independently. The async agent flows through {@link SlaveTemplate#provision} directly while the bypass agent
     * flows through {@code getNewOrExistingAvailableSlave}; the two choke points are disjoint, so each agent is
     * tracked exactly once -- two agents, two activities, no double counting and no runtime guard flag.
     */
    @Test
    void asyncAndBypassPathsAreCountedIndependently() throws Exception {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);
        Label label = r.jenkins.getLabel(template.getLabelString());

        // Async path: one planned agent, its activity created exactly as the NodeProvisioner would.
        Collection<PlannedNode> planned = cloud.provision(label, 1);
        assertEquals(1, planned.size(), "exactly one planned agent for one unit of excess workload");
        TrackedPlannedNode tracked = (TrackedPlannedNode) planned.iterator().next();
        CloudStatistics.ProvisioningListener.get().onStarted(cloud, label, planned);
        Node asyncNode = tracked.future.get(30, TimeUnit.SECONDS);
        assertNotNull(asyncNode, "the async planned agent must resolve to a real node");

        // Bypass path: one more agent through the synchronous choke point the min-instances checker uses.
        cloud.provision(template, 1);

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertEquals(2, activities.size(), "each path records exactly one activity per agent; neither double counts");

        Set<Integer> activityFingerprints = fingerprints(activities);
        assertEquals(2, activityFingerprints.size(), "the two agents carry two distinct activity identities");

        // The async agent keeps its NodeProvisioner-minted identity and appears in exactly one activity -- proving it
        // did not also acquire a second, style-B activity at the bypass choke point.
        EC2AbstractSlave asyncSlave = (EC2AbstractSlave) asyncNode;
        assertNotNull(asyncSlave.getId(), "the async agent must carry a cloud-stats identity");
        assertEquals(
                tracked.getId().getFingerprint(),
                asyncSlave.getId().getFingerprint(),
                "the async agent keeps its NodeProvisioner identity, not a bypass one");
        assertTrue(
                activityFingerprints.contains(asyncSlave.getId().getFingerprint()),
                "the async agent must be tracked by exactly one activity, not double counted");
    }

    private List<EC2AbstractSlave> ec2Agents() {
        return r.jenkins.getNodes().stream()
                .filter(EC2AbstractSlave.class::isInstance)
                .map(EC2AbstractSlave.class::cast)
                .toList();
    }

    private static Set<Integer> fingerprints(List<ProvisioningActivity> activities) {
        return activities.stream()
                .map(activity -> activity.getId().getFingerprint())
                .collect(Collectors.toSet());
    }
}
