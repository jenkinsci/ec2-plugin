package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.NodeProvisioner;
import java.lang.reflect.Constructor;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression test for the interaction between {@link NoDelayProvisionerStrategy} and {@code cloud-stats}.
 *
 * <p>When no-delay provisioning is enabled the strategy provisions on demand and short-circuits the strategy chain,
 * so Jenkins core's {@code NodeProvisioner.StandardStrategy} never runs. The strategy must therefore fire
 * {@code CloudProvisioningListener.onStarted} itself, exactly as {@code StandardStrategy} does -- otherwise
 * label/pipeline-provisioned agents (the async {@link EC2Cloud#provision(Label, int)} path, tracked as a
 * {@code TrackedPlannedNode}) are never recorded by cloud-stats, even though manually provisioned agents are.
 *
 * <p>The other cloud-stats tests call the listener manually to stand in for the NodeProvisioner loop, so none of them
 * exercise the strategy itself; this test drives {@link NoDelayProvisionerStrategy#apply} directly and asserts the
 * listener was fired by checking that an activity now exists.
 */
@WithJenkins
class NoDelayProvisionerStrategyCloudStatsTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void noDelayProvisioningRecordsCloudStatsActivity() throws Exception {
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, template);
        cloud.setNoDelayProvisioning(true);

        Label label = r.jenkins.getLabel(template.getLabelString());

        assertEquals(
                0,
                CloudStatistics.get().getActivities().size(),
                "no activity should exist before the strategy provisions");

        // One queued task and no capacity: the strategy must provision one agent from the no-delay cloud.
        NodeProvisioner.StrategyDecision decision = new NoDelayProvisionerStrategy().apply(strategyState(label, 1));

        assertEquals(
                NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED,
                decision,
                "the no-delay strategy should satisfy the demand and complete provisioning");
        assertEquals(
                1,
                CloudStatistics.get().getActivities().size(),
                "the strategy must fire CloudProvisioningListener.onStarted so cloud-stats records the agent");
        assertTrue(
                CloudStatistics.get().getActivities().stream()
                        .allMatch(a -> cloud.name.equals(a.getId().getCloudName())),
                "the recorded activity must belong to the provisioning cloud");
    }

    /**
     * Builds a {@code NodeProvisioner.StrategyState} with the given queue demand and no available capacity.
     * Its only constructor is package-private in Jenkins core, so it is reached reflectively; the real
     * {@link NodeProvisioner} for the label supplies the (empty) planned-capacity snapshot.
     */
    private NodeProvisioner.StrategyState strategyState(Label label, int queueLength) throws Exception {
        LoadStatistics.LoadStatisticsSnapshot snapshot = LoadStatistics.LoadStatisticsSnapshot.builder()
                .withQueueLength(queueLength)
                .build();
        Constructor<NodeProvisioner.StrategyState> ctor = NodeProvisioner.StrategyState.class.getDeclaredConstructor(
                NodeProvisioner.class, LoadStatistics.LoadStatisticsSnapshot.class, Label.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(label.nodeProvisioner, snapshot, label, 0);
    }
}
