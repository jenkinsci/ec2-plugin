package hudson.plugins.ec2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.when;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import java.io.IOException;
import java.util.Collections;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class InstanceStopTimerTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private AmazonEC2Cloud testCloud;

    @Before
    public void init() throws Exception {
        testCloud = getMockCloud();
        r.jenkins.clouds.add(testCloud);
        Node node = getNode();
        r.jenkins.setNodes(Collections.singletonList(node));
    }

    @Test
    public void testIdleNodeShouldBeStopped() throws IOException, InterruptedException {
        Computer computer = mock(Computer.class);
        when(computer.isConnecting()).thenReturn(false);
        Executor executor = mock(Executor.class);
        when(executor.isIdle()).thenReturn(true);
        when(executor.getIdleStartMilliseconds()).thenReturn(0L);
        when(computer.getAllExecutors()).thenReturn(Collections.singletonList(executor));
        TestableStopTimer stopTimer = new TestableStopTimer(computer);
        stopTimer.execute(null);
        verify(testCloud, times(1)).stopNode(any());
    }

    @Test
    public void testNoComputer() throws IOException, InterruptedException {
        TestableStopTimer stopTimer = new TestableStopTimer(null);
        stopTimer.execute(null);
        verify(testCloud, times(0)).stopNode(any());
    }

    @Test
    public void testNodeIsConnecting() throws IOException, InterruptedException {
        Computer computer = mock(Computer.class);
        when(computer.isConnecting()).thenReturn(true);
        when(computer.isOnline()).thenReturn(false);
        TestableStopTimer stopTimer = new TestableStopTimer(computer);
        stopTimer.execute(null);
        verify(testCloud, times(0)).stopNode(any());
    }

    @Test
    public void testNonIdleNodeShouldNotStop() throws IOException, InterruptedException {
        Computer computer = mock(Computer.class);
        when(computer.isConnecting()).thenReturn(false);
        Executor executor = mock(Executor.class);
        when(executor.isIdle()).thenReturn(false);
        when(executor.getIdleStartMilliseconds()).thenReturn(System.currentTimeMillis());
        when(computer.getAllExecutors()).thenReturn(Collections.singletonList(executor));
        TestableStopTimer stopTimer = new TestableStopTimer(computer);
        stopTimer.execute(null);
        verify(testCloud, times(0)).stopNode(any());
    }

    private Node getNode() {
        Node node = mock(Node.class);
        when(node.getNodeName()).thenReturn("Test Node");
        return node;
    }

    private AmazonEC2Cloud getMockCloud() {
        AmazonEC2Cloud cloud = mock(AmazonEC2Cloud.class);
        when(cloud.isStartStopNodes()).thenReturn(true);
        when(cloud.getMaxIdleMinutes()).thenReturn("2");
        when(cloud.isEc2Node(any())).thenReturn(true);
        return cloud;
    }

    private static class TestableStopTimer extends InstanceStopTimer {
        private Computer computer;

        public TestableStopTimer(Computer testComputer) {
            computer = testComputer;
        }

        @Override
        protected Computer getComputer(Node node) {
            return computer;
        }
    }
}
