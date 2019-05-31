package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.InstanceType;

import hudson.slaves.NodeProperty;
import hudson.model.Executor;
import hudson.model.Node;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class EC2RetentionStrategyTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    final AtomicBoolean idleTimeoutCalled = new AtomicBoolean(false);
    final AtomicBoolean terminateCalled = new AtomicBoolean(false);

    @Test
    public void testOnBillingHourRetention() throws Exception {
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2");
        List<int[]> upTime = new ArrayList<int[]>();
        List<Boolean> expected = new ArrayList<Boolean>();
        upTime.add(new int[] { 58, 0 });
        expected.add(true);
        upTime.add(new int[] { 57, 59 });
        expected.add(false);
        upTime.add(new int[] { 59, 00 });
        expected.add(true);
        upTime.add(new int[] { 59, 30 });
        expected.add(true);
        upTime.add(new int[] { 60, 00 });
        expected.add(false);

        for (int i = 0; i < upTime.size(); i++) {
            int[] t = upTime.get(i);
            EC2Computer computer = computerWithIdleTime(t[0], t[1]);
            rs.check(computer);
            assertEquals("Expected " + t[0] + "m" + t[1] + "s to be " + expected.get(i), (boolean) expected.get(i), idleTimeoutCalled.get());
            // reset the assumption
            idleTimeoutCalled.set(false);
        }
    }

    private EC2Computer computerWithIdleTime(final int minutes, final int seconds) throws Exception {
        final EC2AbstractSlave slave = new EC2AbstractSlave("name", "id", "description", "fs", 1, null, "label", null, null, "init", "tmpDir", new ArrayList<NodeProperty<?>>(), "remote", "jvm", false, "idle", null, "cloud", false, Integer.MAX_VALUE, null, ConnectionStrategy.PRIVATE_IP, -1) {
            @Override
            public void terminate() {
            }

            @Override
            public String getEc2Type() {
                return null;
            }

            @Override
            void idleTimeout() {
                idleTimeoutCalled.set(true);
            }
        };
        EC2Computer computer = new EC2Computer(slave) {

            @Override
            public EC2AbstractSlave getNode() {
                return slave;
            }

            @Override
            public long getUptime() throws AmazonClientException, InterruptedException {
                return ((minutes * 60L) + seconds) * 1000L;
            }

            @Override
            public boolean isOffline() {
                return false;
            }

            @Override
            public InstanceState getState() {
                return InstanceState.RUNNING;
            }
            
            @Override
            public SlaveTemplate getSlaveTemplate() {
                return new SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "AMI description", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet-123 subnet-456", null, null, true, null, "", false, false, "", false, "");
            }
        };
        assertTrue(computer.isIdle());
        assertTrue(computer.isOnline());
        return computer;
    }

    @Test
    public void testOnUsageCountRetention() throws Exception {
        EC2RetentionStrategy rs = new EC2RetentionStrategy("0");
        List<Integer> usageCounts = new ArrayList<Integer>();
        List<Boolean> expected = new ArrayList<Boolean>();
        usageCounts.add(5);
        expected.add(false);

        for (int i = 0; i < usageCounts.size(); i++) {
            int usageCount = usageCounts.get(i);
            // We test usageCount down to -1 which is unlimited agent uses
            while (--usageCount > -2 ) {
                EC2Computer computer = computerWithUsageLimit(usageCount);
                Executor executor = new Executor(computer, 0);
                rs.taskAccepted(executor, null);
                if (!computer.isAcceptingTasks()) {
                    rs.taskCompleted(executor, null, 0);
                }
                // As we want to terminate agent both for usageCount 1 & 0 - setting this to true
                if (usageCount == 1 || usageCount == 0) {
                    assertEquals("Expected " + usageCount + " to be " + true, (boolean) true, terminateCalled.get());
                    // Reset the assumption
                    terminateCalled.set(false);
                } else {
                    assertEquals("Expected " + usageCount + " to be " + expected.get(i), (boolean) expected.get(i), terminateCalled.get());
                }
            }

        }
    }

    private EC2Computer computerWithUsageLimit(final int usageLimit) throws Exception {
        final EC2AbstractSlave slave = new EC2AbstractSlave("name", "id", "description", "fs", 1, null, "label", null, null, "init", "tmpDir", new ArrayList<NodeProperty<?>>(), "remote", "jvm", false, "idle", null, "cloud", false, Integer.MAX_VALUE, null, ConnectionStrategy.PRIVATE_IP, usageLimit) {
            @Override
            public void terminate() {
                terminateCalled.set(true);
            }

            @Override
            public String getEc2Type() {
                return null;
            }
        };
        EC2Computer computer = new EC2Computer(slave) {
            @Override
            public EC2AbstractSlave getNode() {
                return slave;
            }
        };
        return computer;
    }
}
