package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.InstanceType;

import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.PrivateKeyHelper;
import hudson.slaves.NodeProperty;
import hudson.model.Executor;
import hudson.model.Node;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EC2RetentionStrategyTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    final AtomicBoolean idleTimeoutCalled = new AtomicBoolean(false);
    final AtomicBoolean terminateCalled = new AtomicBoolean(false);
    private static ZoneId zoneId = ZoneId.systemDefault();

    @Test
    public void testOnBillingHourRetention() throws Exception {
        List<int[]> upTime = new ArrayList<int[]>();
        List<Boolean> expected = new ArrayList<Boolean>();
        upTime.add(new int[] { 58, 0 });
        expected.add(true);
        upTime.add(new int[] { 57, 59 });
        expected.add(false);
        upTime.add(new int[] { 59, 0 });
        expected.add(true);
        upTime.add(new int[] { 59, 30 });
        expected.add(true);
        upTime.add(new int[] { 60, 0 });
        expected.add(false);

        for (int i = 0; i < upTime.size(); i++) {
            int[] t = upTime.get(i);
            EC2Computer computer = computerWithIdleTime(t[0], t[1]);
            EC2RetentionStrategy rs = new EC2RetentionStrategy("-2");
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

    @Test
    public void testInternalCheckRespectsWait() throws Exception {
        List<Boolean> expected = new ArrayList<Boolean>();
        EC2Computer computer = computerWithIdleTime(0, 0);
        List<int[]> upTimeAndCheckAfter = new ArrayList<int[]>();

        upTimeAndCheckAfter.add(new int[] { 0, -1 });
        expected.add(true);
        upTimeAndCheckAfter.add(new int[] { 30, 60 });
        expected.add(false);
        upTimeAndCheckAfter.add(new int[] { 60, 60 });
        expected.add(false);
        upTimeAndCheckAfter.add(new int[] { 61, 60 });
        expected.add(true);

        Instant now = Instant.now();
        for (int i = 0; i < upTimeAndCheckAfter.size(); i++) {
            int[] t = upTimeAndCheckAfter.get(i);
            int startingUptime = t[0];
            boolean expectCallCheck = expected.get(i);
            long nextCheckAfter = now.plusSeconds(t[1]).toEpochMilli();
            EC2RetentionStrategy rs;
            if (i > 0) {
                Clock clock = Clock.fixed(now.plusSeconds(startingUptime), zoneId);
                rs = new EC2RetentionStrategy("1", clock, nextCheckAfter);

            } else {
                rs = new EC2RetentionStrategy("1");
            }
            rs.check(computer);
            String action = expected.get(i) ? "call" : "not call";
            long newNextCheckAfter = rs.getNextCheckAfter();
            assertEquals(String.format("Expected elapsed time of %s ms to %s internalCheck.", startingUptime, action), expectCallCheck, nextCheckAfter != newNextCheckAfter);
        }
    }
//
//    @Test
//    public void testRetentionDespiteIdleWithMinimumInstances() throws Exception {
//
//        SlaveTemplate template = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "default", "foo",
//          InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null,
//          "-Xmx1g", false, "subnet 456", null, null, 2, "10", null, true, true, false, "", false, "", false, false,
//          true, ConnectionStrategy.PRIVATE_IP, 0);
//        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", PrivateKeyHelper.generate(), "3",
//          Collections
//            .singletonList(template), "roleArn", "roleSessionName");
//        r.jenkins.clouds.add(cloud);
//        r.configRoundtrip();
//
//        List<EC2Computer> computers = Arrays.stream(r.jenkins.getComputers())
//          .filter(computer -> computer instanceof EC2Computer)
//          .map(computer -> (EC2Computer) computer)
//          .collect(Collectors.toList());
//
//        // Should have two slaves before any checking
//        assertEquals(2, computers.size());
//
//        Instant now = Instant.now();
//        Clock clock = Clock.fixed(now, zoneId);
//        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
//        rs.check(computers.get(0));
//
//        computers = Arrays.stream(r.jenkins.getComputers())
//          .filter(computer -> computer instanceof EC2Computer)
//          .map(computer -> (EC2Computer) computer)
//          .collect(Collectors.toList());
//
//        // Should have two slaves after check too
//        assertEquals(2, computers.size());
//        assertEquals(2, AmazonEC2FactoryMockImpl.instances.size());
//
//        // Add a new slave
//        cloud.provision(template, 1);
//
//        computers = Arrays.stream(r.jenkins.getComputers())
//          .filter(computer -> computer instanceof EC2Computer)
//          .map(computer -> (EC2Computer) computer)
//          .collect(Collectors.toList());
//
//        // Should have three slaves before any checking
//        assertEquals(3, computers.size());
//        assertEquals(3, AmazonEC2FactoryMockImpl.instances.size());
//
//        rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
//        rs.check(computers.get(0));
//
//        computers = Arrays.stream(r.jenkins.getComputers())
//          .filter(computer -> computer instanceof EC2Computer)
//          .map(computer -> (EC2Computer) computer)
//          .collect(Collectors.toList());
//
//        // Should have two slaves after check
//        assertEquals(2, computers.size());
//        assertEquals(2, AmazonEC2FactoryMockImpl.instances.size());
//    }
}
