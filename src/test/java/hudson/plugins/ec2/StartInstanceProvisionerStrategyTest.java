package hudson.plugins.ec2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.when;

import hudson.model.Label;
import hudson.model.LoadStatistics.LoadStatisticsSnapshot;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.StrategyState;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.powermock.core.classloader.annotations.PrepareForTest;

@PrepareForTest(NodeProvisioner.StrategyState.class)
public class StartInstanceProvisionerStrategyTest {
    @Rule
    public JenkinsRule r = new JenkinsRule();

    private AmazonEC2Cloud testCloud;

    private static final String TEST_HOST_LABEL = "testHost";

    @Before
    public void init() throws Exception {
        testCloud = getMockCloud();
        r.jenkins.clouds.add(testCloud);
        Node node = getNode();
        r.jenkins.setNodes(Collections.singletonList(node));
    }

    @Test
    public void testNodeShouldBeStarted() throws Exception {
        TestableStartInstanceProvisioner provisioner = new TestableStartInstanceProvisioner(false);
        provisioner.apply(getStrategyState(1, 0));
        verify(testCloud, times(1)).startNode(any());
    }

    @Test
    public void testNeedMetDontStart() throws Exception {
        TestableStartInstanceProvisioner provisioner = new TestableStartInstanceProvisioner(false);
        provisioner.apply(getStrategyState(1, 1));
        verify(testCloud, times(0)).startNode(any());
    }

    @Test
    public void testNodeIsOnline() throws Exception {
        TestableStartInstanceProvisioner provisioner = new TestableStartInstanceProvisioner(true);
        provisioner.apply(getStrategyState(1, 0));
        verify(testCloud, times(0)).startNode(any());
    }

    private Node getNode() {
        Node node = mock(Node.class);
        when(node.getNumExecutors()).thenReturn(0);
        when(node.getNodeName()).thenReturn("Test Node");
        Set<LabelAtom> labels = new HashSet<>();
        LabelAtom ec2Label = new LabelAtom(TEST_HOST_LABEL);
        labels.add(ec2Label);
        when(node.getAssignedLabels()).thenReturn(labels);
        return node;
    }

    private AmazonEC2Cloud getMockCloud() {
        AmazonEC2Cloud cloud = mock(AmazonEC2Cloud.class);
        when(cloud.isStartStopNodes()).thenReturn(true);
        when(cloud.getMaxIdleMinutes()).thenReturn("2");
        when(cloud.isEc2Node(any())).thenReturn(true);
        return cloud;
    }

    private StrategyState getStrategyState(int queueLength, int planned) throws Exception {
        Label label = mock(Label.class);
        when(label.getExpression()).thenReturn(TEST_HOST_LABEL);
        LoadStatisticsSnapshot snapshot = LoadStatisticsSnapshot.builder().withQueueLength(queueLength).build();
        NodeProvisioner nodeProvisioner = new NodeProvisioner(label, null);
        NodeProvisioner.StrategyState strategyState = null;
        for (Constructor constructor : NodeProvisioner.StrategyState.class.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 4) {
                constructor.setAccessible(true);
                strategyState = (StrategyState) constructor.newInstance(nodeProvisioner, snapshot, label, planned);
                break;
            }
        }
        return strategyState;
    }

    private static class TestableStartInstanceProvisioner extends StartInstanceProvisionerStrategy {
        private boolean nodeOnline;

        public TestableStartInstanceProvisioner(boolean nodeOnline) {
            this.nodeOnline = nodeOnline;
        }
        @Override protected boolean isNodeOnline(Node node) {
            return nodeOnline;
        }
    }
}
