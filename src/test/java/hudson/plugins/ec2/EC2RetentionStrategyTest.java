package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.InstanceType;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.ResourceList;
import hudson.model.Queue.Executable;
import hudson.model.Queue.Task;
import hudson.model.queue.CauseOfBlockage;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import hudson.plugins.ec2.util.MinimumNumberOfInstancesTimeRangeConfig;
import hudson.plugins.ec2.util.PrivateKeyHelper;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.security.Permission;import hudson.model.Executor;
import hudson.slaves.NodeProperty;
import hudson.slaves.OfflineCause;
import jenkins.util.NonLocalizable;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.acegisecurity.Authentication;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.testcontainers.shaded.org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jvnet.hudson.test.LoggerRule;

import java.security.Security;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;
import jenkins.util.NonLocalizable;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

public class EC2RetentionStrategyTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Rule
    public LoggerRule logging = new LoggerRule();

    final AtomicBoolean idleTimeoutCalled = new AtomicBoolean(false);
    final AtomicBoolean terminateCalled = new AtomicBoolean(false);
    private static final ZoneId zoneId = ZoneId.systemDefault();

    @Test
    public void testOnBillingHourRetention() throws Exception {
        List<int[]> upTime = new ArrayList<>();
        List<Boolean> expected = new ArrayList<>();
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
            EC2Computer computer = computerWithUpTime(t[0], t[1]);
            EC2RetentionStrategy rs = new EC2RetentionStrategy("-2");
            checkRetentionStrategy(rs, computer);
            assertEquals("Expected " + t[0] + "m" + t[1] + "s to be " + expected.get(i), (boolean) expected.get(i), idleTimeoutCalled.get());
            // reset the assumption
            idleTimeoutCalled.set(false);
        }
    }

    @Test
    public void testRetentionWhenQueueHasWaitingItemForThisNode() throws Exception {
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2");
        EC2Computer computer = computerWithIdleTime(59, 0);
        final Label selfLabel = computer.getNode().getSelfLabel();
        final Queue queue = Jenkins.get().getQueue();
        final Task task = taskForLabel(selfLabel, false);
        queue.schedule(task, 500);
        checkRetentionStrategy(rs, computer);
        assertFalse("Expected computer to be left running", idleTimeoutCalled.get());
        queue.cancel(task);
        EC2RetentionStrategy rs2 = new EC2RetentionStrategy("-2");
        checkRetentionStrategy(rs2, computer);
        assertTrue("Expected computer to be idled", idleTimeoutCalled.get());
    }

    @Test
    public void testRetentionWhenQueueHasBlockedItemForThisNode() throws Exception {
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2");
        EC2Computer computer = computerWithIdleTime(59, 0);
        final Label selfLabel = computer.getNode().getSelfLabel();
        final Queue queue = Jenkins.get().getQueue();
        final Task task = taskForLabel(selfLabel, true);
        queue.schedule(task, 0);
        checkRetentionStrategy(rs, computer);
        assertFalse("Expected computer to be left running", idleTimeoutCalled.get());
        queue.cancel(task);
        EC2RetentionStrategy rs2 = new EC2RetentionStrategy("-2");
        checkRetentionStrategy(rs2, computer);
        assertTrue("Expected computer to be idled", idleTimeoutCalled.get());
    }

    private interface AccessControlledTask extends Queue.Task, AccessControlled {
    }

    private Queue.Task taskForLabel(final Label label, boolean blocked) {
        final CauseOfBlockage cob = blocked ? new CauseOfBlockage() {
            @Override
            public String getShortDescription() {
                return "Blocked";
            }
        } : null;
        return new AccessControlledTask() {
            @NonNull
            public ACL getACL() {
                return new ACL() {
                    public boolean hasPermission(@NonNull Authentication a, @NonNull Permission permission) {
                        return true;
                    }
                };
            }
            public ResourceList getResourceList() {
                return null;
            }

            public Node getLastBuiltOn() {
                return null;
            }

            public long getEstimatedDuration() {
                return -1;
            }

            public Label getAssignedLabel() {
                return label;
            }

            public Executable createExecutable() {
                return null;
            }

            public String getDisplayName() {
                return null;
            }

            @Override
            public CauseOfBlockage getCauseOfBlockage() {
                return cob;
            }

            public boolean hasAbortPermission() {
                return false;
            }

            public String getUrl() {
                return null;
            }

            public String getName() {
                return null;
            }

            public String getFullDisplayName() {
                return null;
            }

            public void checkAbortPermission() {
            }
        };
    }

    private EC2Computer computerWithIdleTime(final int minutes, final int seconds) throws Exception {
        return computerWithIdleTime(minutes, seconds, false, null);
    }

    /*
     * Creates a computer with the params passed. If isOnline is null, the computer returns the real value, otherwise,
     * the computer returns the value established.
     */
    private EC2Computer computerWithIdleTime(final int minutes, final int seconds, final Boolean isOffline, final Boolean isConnecting) throws Exception {
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

            private final long launchedAtMs = Instant.now().minus(Duration.ofSeconds(minutes * 60L + seconds)).toEpochMilli();

            @Override
            public EC2AbstractSlave getNode() {
                return slave;
            }

            @Override
            public long getUptime() throws AmazonClientException, InterruptedException {
                return ((minutes * 60L) + seconds) * 1000L;
            }

            @Override
            public long getLaunchTime() throws InterruptedException {
                return this.launchedAtMs;
            }

            @Override
            public boolean isOffline() {
                return isOffline == null ? super.isOffline() : isOffline;
            }

            @Override
            public InstanceState getState() {
                return InstanceState.RUNNING;
            }

            @Override
            public SlaveTemplate getSlaveTemplate() {
                return new SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "AMI description", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet-123 subnet-456", null, null, true, null, "", false, false, "", false, "");
            }

            @Override
            public boolean isConnecting() {
                return isConnecting == null ? super.isConnecting() : isConnecting;
            }
        };
        assertTrue(computer.isIdle());
        assertTrue(isOffline == null || computer.isOffline() == isOffline);
        return computer;
    }

    private EC2Computer computerWithUpTime(final int minutes, final int seconds) throws Exception {
        return computerWithUpTime(minutes, seconds, false, null);
    }

    /*
     * Creates a computer with the params passed. If isOnline is null, the computer returns the real value, otherwise,
     * the computer returns the value established.
     */
    private EC2Computer computerWithUpTime(final int minutes, final int seconds, final Boolean isOffline, final Boolean isConnecting) throws Exception {
        idleTimeoutCalled.set(false);
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
            private final long launchedAtMs = Instant.now().minus(Duration.ofSeconds(minutes * 60L + seconds)).toEpochMilli();

            @Override
            public EC2AbstractSlave getNode() {
                return slave;
            }

            @Override
            public long getUptime() throws AmazonClientException, InterruptedException {
                return ((minutes * 60L) + seconds) * 1000L;
            }

            @Override
            public long getLaunchTime() throws InterruptedException {
                return this.launchedAtMs;
            }

            @Override
            public boolean isOffline() {
                return isOffline == null ? super.isOffline() : isOffline;
            }

            @Override
            public InstanceState getState() {
                return InstanceState.RUNNING;
            }

            @Override
            public SlaveTemplate getSlaveTemplate() {
                return new SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "AMI description", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet-123 subnet-456", null, null, true, null, "", false, false, "", false, "");
            }

            @Override
            public boolean isConnecting() {
                return isConnecting == null ? super.isConnecting() : isConnecting;
            }
        };
        assertTrue(computer.isIdle());
        assertTrue(isOffline == null || computer.isOffline() == isOffline);
        return computer;
    }

    @Test
    public void testOnUsageCountRetention() throws Exception {
        EC2RetentionStrategy rs = new EC2RetentionStrategy("0");
        List<Integer> usageCounts = new ArrayList<>();
        List<Boolean> expected = new ArrayList<>();
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
        return new EC2Computer(slave) {
            @Override
            public EC2AbstractSlave getNode() {
                return slave;
            }
        };
    }

    /**
     * Even though the computer is offline, we terminate it if it's not connecting now and the idle timeout expired.
     */
    @Test
    public void testTerminateOfflineComputerIfNotConnecting() throws Exception {
        logging.record(hudson.plugins.ec2.EC2RetentionStrategy.class, Level.FINE);
        logging.capture(5);

        // The clock of the retention is set to 5 minutes in the future to pretend the computer is idle. The idle
        // timeout is in minutes and the minimum is 1.
        Instant twoMinutesAgo = Instant.now().plus(Duration.ofMinutes(5));
        long nextCheckAfter = twoMinutesAgo.toEpochMilli();
        Clock clock = Clock.fixed(twoMinutesAgo.plusSeconds(1), zoneId);
        EC2RetentionStrategy rs = new EC2RetentionStrategy("1", clock, nextCheckAfter);

        OfflineCause cause = OfflineCause.create(new NonLocalizable("Testing terminate on offline computer"));

        // A computer returning the real isOffline value and still connecting
        EC2Computer computer = computerWithUpTime(0, 0, null, true);
        computer.setTemporarilyOffline(true, cause);
        // We don't terminate this one
        rs.check(computer);
        assertThat("The computer is not terminated, it should still accept tasks", idleTimeoutCalled.get(), equalTo(false));
        assertThat(logging.getMessages(), hasItem(containsString("connecting and still offline, will check if the launch timeout has expired")));

        // A computer returning the real isOffline value and not connecting
        rs = new EC2RetentionStrategy("1", clock, nextCheckAfter);
        EC2Computer computer2 = computerWithUpTime(0, 0, null, false);
        computer.setTemporarilyOffline(true, cause);
        // We terminate this one
        rs.check(computer2);
        assertThat("The computer is terminated, it should not accept more tasks", idleTimeoutCalled.get(), equalTo(true));
        assertThat(logging.getMessages(), hasItem(containsString("offline but not connecting, will check if it should be terminated because of the idle time configured")));
    }


    /**
     * Do not terminate an instance if a computer just launched, and the
     * retention strategy timeout has not expired yet. The quirks with idle timeout
     * are now correctly accounted for nodes that have been stopped and started
     * again (i.e termination policy is stop, rather than terminate).
     *
     * How does the test below work: for our "mock" EC2Computer, idle start time
     * is always the time when the object has been created, and we cannot
     * easily control idle start time in a test suite as the relevant method to override
     * is declared final.
     *
     * We can achieve the same result where idle start time is < launch time
     * by manipulating the node launch time variables. Since, as we said, idle
     * start time is always now, what we end up doing is tricking the node
     * into returning a launch time in the future. To do this we pass a negative
     * value for minutes to {@link #computerWithUpTime(int, int, Boolean, Boolean)}.
     *
     * Now if we set retention time to something bigger than the computer uptime
     * we can reproduce the issue, and prove its resolution.
     *
     * The time at which we perform the check must be < than computer uptime
     * plus the retention interval.
     */
    @Test
    public void testDoNotTerminateInstancesJustBooted() throws Exception {
        logging.record(hudson.plugins.ec2.EC2RetentionStrategy.class, Level.FINE);
        logging.capture(5);
        final int COMPUTER_UPTIME_MINUTES=5;
        final int RETENTION_MINUTES=5;
        final int CHECK_TIME_MINUTES=COMPUTER_UPTIME_MINUTES+RETENTION_MINUTES-1;
        final Instant checkTime = Instant.now().plus(Duration.ofMinutes(CHECK_TIME_MINUTES));
        new EC2RetentionStrategy(
            String.format("%d", RETENTION_MINUTES),
            Clock.fixed(checkTime.plusSeconds(1), zoneId),
            checkTime.toEpochMilli()
        ).check(computerWithUpTime(-COMPUTER_UPTIME_MINUTES, 0, null, false));
        assertThat("The computer is terminated, but should not be", idleTimeoutCalled.get(), equalTo(false));
        assertThat(logging.getMessages(), hasItem(containsString("offline but not connecting, will check if it should be terminated because of the idle time configured")));
    }

    /**
     * Ensure that we terminate instances that stay unconnected, as soon as
     * termination time expires.
     */
    @Test
    public void testCleanupUnconnectedInstanceAfterTerminationTime() throws Exception {
        logging.record(hudson.plugins.ec2.EC2RetentionStrategy.class, Level.FINE);
        logging.capture(5);
        final int COMPUTER_UPTIME_MINUTES=5;
        final int RETENTION_MINUTES=5;
        final int CHECK_TIME_MINUTES=COMPUTER_UPTIME_MINUTES+RETENTION_MINUTES+1;
        final Instant checkTime = Instant.now().plus(Duration.ofMinutes(CHECK_TIME_MINUTES));
        new EC2RetentionStrategy(
            String.format("%d", RETENTION_MINUTES),
            Clock.fixed(checkTime.plusSeconds(1), zoneId),
            checkTime.toEpochMilli()
        ).check(computerWithUpTime(-COMPUTER_UPTIME_MINUTES, 0, null, false));
        assertThat("The computer is not terminated, but should be", idleTimeoutCalled.get(), equalTo(true));
        assertThat(logging.getMessages(), hasItem(containsString("offline but not connecting, will check if it should be terminated because of the idle time configured")));
    }

    @Test
    public void testInternalCheckRespectsWait() throws Exception {
        List<Boolean> expected = new ArrayList<>();
        EC2Computer computer = computerWithUpTime(0, 0);
        List<int[]> upTimeAndCheckAfter = new ArrayList<>();

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
            checkRetentionStrategy(rs, computer);
            String action = expected.get(i) ? "call" : "not call";
            long newNextCheckAfter = rs.getNextCheckAfter();
            assertEquals(String.format("Expected elapsed time of %s ms to %s internalCheck.", startingUptime, action), expectCallCheck, nextCheckAfter != newNextCheckAfter);
        }
    }

    @Test
    public void testRetentionDespiteIdleWithMinimumInstances() throws Exception {

        SlaveTemplate template = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "default", "foo",
          InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null,
          "-Xmx1g", false, "subnet 456", null, null, 2, 0, "10", null, true, true, false, "", false, "", false, false,
          true, ConnectionStrategy.PRIVATE_IP, 0, Collections.emptyList());
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", null, "ghi", "3",
          Collections
            .singletonList(template), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        List<EC2Computer> computers = Arrays.stream(r.jenkins.getComputers())
          .filter(computer -> computer instanceof EC2Computer)
          .map(computer -> (EC2Computer) computer)
          .collect(Collectors.toList());

        // Should have two agents before any checking
        assertEquals(2, computers.size());

        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, zoneId);
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
        checkRetentionStrategy(rs, computers.get(0));

        computers = Arrays.stream(r.jenkins.getComputers())
          .filter(computer -> computer instanceof EC2Computer)
          .map(computer -> (EC2Computer) computer)
          .collect(Collectors.toList());

        // Should have two agents after check too
        assertEquals(2, computers.size());
        assertEquals(2, AmazonEC2FactoryMockImpl.instances.size());

        // Add a new agent
        cloud.provision(template, 1);

        computers = Arrays.stream(r.jenkins.getComputers())
          .filter(computer -> computer instanceof EC2Computer)
          .map(computer -> (EC2Computer) computer)
          .collect(Collectors.toList());

        // Should have three agents before any checking
        assertEquals(3, computers.size());
        assertEquals(3, AmazonEC2FactoryMockImpl.instances.size());

        rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
        checkRetentionStrategy(rs, computers.get(0));

        computers = Arrays.stream(r.jenkins.getComputers())
          .filter(computer -> computer instanceof EC2Computer)
          .map(computer -> (EC2Computer) computer)
          .collect(Collectors.toList());

        // Should have two agents after check
        assertEquals(2, computers.size());
        assertEquals(2, AmazonEC2FactoryMockImpl.instances.size());
    }

    @Test
    public void testRetentionDespiteIdleWithMinimumInstanceActiveTimeRange() throws Exception {
        SlaveTemplate template = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "default", "foo",
            InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null,
            "-Xmx1g", false, "subnet 456", null, null, 2, 0, "10", null, true, true, false, "", false, "", false, false,
            true, ConnectionStrategy.PRIVATE_IP, 0, Collections.emptyList());

        MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig = new MinimumNumberOfInstancesTimeRangeConfig();
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeFrom("11:00");
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeTo("15:00");
        minimumNumberOfInstancesTimeRangeConfig.setMonday(false);
        minimumNumberOfInstancesTimeRangeConfig.setTuesday(true);
        template.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        LocalDateTime localDateTime = LocalDateTime.of(2019, Month.SEPTEMBER, 24, 12, 0); //Tuesday

        //Set fixed clock to be able to test properly
        MinimumInstanceChecker.clock = Clock.fixed(localDateTime.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", null, "ghi", "3",
            Collections
                .singletonList(template), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        List<EC2Computer> computers = Arrays.stream(r.jenkins.getComputers())
            .filter(computer -> computer instanceof EC2Computer)
            .map(computer -> (EC2Computer) computer)
            .collect(Collectors.toList());

        // Should have two agents before any checking
        assertEquals(2, computers.size());

        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, zoneId);
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
        checkRetentionStrategy(rs, computers.get(0));

        computers = Arrays.stream(r.jenkins.getComputers())
            .filter(computer -> computer instanceof EC2Computer)
            .map(computer -> (EC2Computer) computer)
            .collect(Collectors.toList());

        // Should have two agents after check too
        assertEquals(2, computers.size());
        assertEquals(2, AmazonEC2FactoryMockImpl.instances.size());

    }

    @Test
    public void testRetentionIdleWithMinimumInstanceInactiveTimeRange() throws Exception {
        SlaveTemplate template = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "default", "foo",
            InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null,
            "-Xmx1g", false, "subnet 456", null, null, 2, 0, "10", null, true, true, false, "", false, "", false, false,
            true, ConnectionStrategy.PRIVATE_IP, 0, Collections.emptyList());

        MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig = new MinimumNumberOfInstancesTimeRangeConfig();
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeFrom("11:00");
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeTo("15:00");
        minimumNumberOfInstancesTimeRangeConfig.setMonday(false);
        minimumNumberOfInstancesTimeRangeConfig.setTuesday(true);
        template.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        LocalDateTime localDateTime = LocalDateTime.of(2019, Month.SEPTEMBER, 24, 10, 0); //Tuesday before range

        //Set fixed clock to be able to test properly
        MinimumInstanceChecker.clock = Clock.fixed(localDateTime.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", PrivateKeyHelper.generate(), "3",
            Collections
                .singletonList(template), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        List<EC2Computer> computers = Arrays.stream(r.jenkins.getComputers())
            .filter(computer -> computer instanceof EC2Computer)
            .map(computer -> (EC2Computer) computer)
            .collect(Collectors.toList());

        // Should have zero agents
        assertEquals(0, computers.size());
    }

    @Test
    public void testRetentionDespiteIdleWithMinimumInstanceActiveTimeRangeAfterMidnight() throws Exception {
        SlaveTemplate template = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "default", "foo",
            InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null,
            "-Xmx1g", false, "subnet 456", null, null, 2, 0, "10", null, true, true, false, "", false, "", false, false,
            true, ConnectionStrategy.PRIVATE_IP, 0, Collections.emptyList());

        MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig = new MinimumNumberOfInstancesTimeRangeConfig();
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeFrom("15:00");
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeTo("03:00");
        minimumNumberOfInstancesTimeRangeConfig.setMonday(false);
        minimumNumberOfInstancesTimeRangeConfig.setTuesday(true);
        template.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        LocalDateTime localDateTime = LocalDateTime.of(2019, Month.SEPTEMBER, 25, 1, 0); //Wednesday

        //Set fixed clock to be able to test properly
        MinimumInstanceChecker.clock = Clock.fixed(localDateTime.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", null, "ghi", "3",
            Collections
                .singletonList(template), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        List<EC2Computer> computers = Arrays.stream(r.jenkins.getComputers())
            .filter(computer -> computer instanceof EC2Computer)
            .map(computer -> (EC2Computer) computer)
            .collect(Collectors.toList());

        // Should have two agents before any checking
        assertEquals(2, computers.size());

        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, zoneId);
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
        checkRetentionStrategy(rs, computers.get(0));

        computers = Arrays.stream(r.jenkins.getComputers())
            .filter(computer -> computer instanceof EC2Computer)
            .map(computer -> (EC2Computer) computer)
            .collect(Collectors.toList());

        // Should have two agents after check too
        assertEquals(2, computers.size());
        assertEquals(2, AmazonEC2FactoryMockImpl.instances.size());
    }

    @Test
    public void testRetentionStopsAfterActiveRangeEnds() throws Exception {
        SlaveTemplate template = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "default", "foo",
            InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null,
            "-Xmx1g", false, "subnet 456", null, null, 2, 0, "10", null, true, true, false, "", false, "", false, false,
            true, ConnectionStrategy.PRIVATE_IP, 0, Collections.emptyList());

        MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig = new MinimumNumberOfInstancesTimeRangeConfig();
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeFrom("11:00");
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeTo("15:00");
        minimumNumberOfInstancesTimeRangeConfig.setMonday(false);
        minimumNumberOfInstancesTimeRangeConfig.setTuesday(true);
        template.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        //Set fixed clock to be able to test properly
        LocalDateTime localDateTime = LocalDateTime.of(2019, Month.SEPTEMBER, 24, 14, 0); //Tuesday
        MinimumInstanceChecker.clock = Clock.fixed(localDateTime.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", null, "ghi", "3",
            Collections
                .singletonList(template), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        List<EC2Computer> computers = Arrays.stream(r.jenkins.getComputers())
            .filter(computer -> computer instanceof EC2Computer)
            .map(computer -> (EC2Computer) computer)
            .collect(Collectors.toList());

        // Should have two agents before any checking
        assertEquals(2, computers.size());

        //Set fixed clock to after active period
        localDateTime = LocalDateTime.of(2019, Month.SEPTEMBER, 24, 16, 0); //Tuesday
        MinimumInstanceChecker.clock = Clock.fixed(localDateTime.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, zoneId);
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
        checkRetentionStrategy(rs, computers.get(0));

        computers = Arrays.stream(r.jenkins.getComputers())
            .filter(computer -> computer instanceof EC2Computer)
            .map(computer -> (EC2Computer) computer)
            .collect(Collectors.toList());

        // Should have 1 agents after check
        assertEquals(1, computers.size());
        assertEquals(1, AmazonEC2FactoryMockImpl.instances.size());
    }

    private static void checkRetentionStrategy(EC2RetentionStrategy rs, EC2Computer c) throws InterruptedException {
        rs.check(c);
        EC2AbstractSlave node = c.getNode();
        assertTrue(node.terminateScheduled.await(10, TimeUnit.SECONDS));
    }
}
