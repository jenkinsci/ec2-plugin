package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.InstanceType;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import hudson.plugins.ec2.util.MinimumNumberOfInstancesTimeRangeConfig;
import hudson.plugins.ec2.util.PrivateKeyHelper;
import hudson.slaves.NodeProperty;
import hudson.slaves.OfflineCause;
import jenkins.util.NonLocalizable;
import net.sf.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EC2RetentionStrategyTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();
    
    @Rule
    public LoggerRule logging = new LoggerRule();
    
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
            checkRetentionStrategy(rs, computer);
            assertEquals("Expected " + t[0] + "m" + t[1] + "s to be " + expected.get(i), (boolean) expected.get(i), idleTimeoutCalled.get());
            // reset the assumption
            idleTimeoutCalled.set(false);
        }
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
        EC2Computer computer = computerWithIdleTime(0, 0, null, true); 
        computer.setTemporarilyOffline(true, cause);
        // We don't terminate this one
        rs.check(computer);
        assertThat("The computer is not terminated, it should still accept tasks", idleTimeoutCalled.get(), equalTo(false));
        assertThat(logging.getMessages(), hasItem(containsString("connecting and still offline, will check if the launch timeout has expired")));
                
        // A computer returning the real isOffline value and not connecting
        rs = new EC2RetentionStrategy("1", clock, nextCheckAfter);
        EC2Computer computer2 = computerWithIdleTime(0, 0, null, false);
        computer.setTemporarilyOffline(true, cause);
        // We terminate this one
        rs.check(computer2);
        assertThat("The computer is terminated, it should not accept more tasks", idleTimeoutCalled.get(), equalTo(true));
        assertThat(logging.getMessages(), hasItem(containsString("offline but not connecting, will check if it should be terminated because of the idle time configured")));
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
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", PrivateKeyHelper.generate(), "3",
          Collections
            .singletonList(template), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        List<EC2Computer> computers = Arrays.stream(r.jenkins.getComputers())
          .filter(computer -> computer instanceof EC2Computer)
          .map(computer -> (EC2Computer) computer)
          .collect(Collectors.toList());

        // Should have two slaves before any checking
        assertEquals(2, computers.size());

        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, zoneId);
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
        checkRetentionStrategy(rs, computers.get(0));

        computers = Arrays.stream(r.jenkins.getComputers())
          .filter(computer -> computer instanceof EC2Computer)
          .map(computer -> (EC2Computer) computer)
          .collect(Collectors.toList());

        // Should have two slaves after check too
        assertEquals(2, computers.size());
        assertEquals(2, AmazonEC2FactoryMockImpl.instances.size());

        // Add a new slave
        cloud.provision(template, 1);

        computers = Arrays.stream(r.jenkins.getComputers())
          .filter(computer -> computer instanceof EC2Computer)
          .map(computer -> (EC2Computer) computer)
          .collect(Collectors.toList());

        // Should have three slaves before any checking
        assertEquals(3, computers.size());
        assertEquals(3, AmazonEC2FactoryMockImpl.instances.size());

        rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
        checkRetentionStrategy(rs, computers.get(0));

        computers = Arrays.stream(r.jenkins.getComputers())
          .filter(computer -> computer instanceof EC2Computer)
          .map(computer -> (EC2Computer) computer)
          .collect(Collectors.toList());

        // Should have two slaves after check
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
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("monday", false);
        jsonObject.put("tuesday", true);
        jsonObject.put("wednesday", false);
        jsonObject.put("thursday", false);
        jsonObject.put("friday", false);
        jsonObject.put("saturday", false);
        jsonObject.put("sunday", false);
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeDays(jsonObject);
        template.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        LocalDateTime localDateTime = LocalDateTime.of(2019, Month.SEPTEMBER, 24, 12, 0); //Tuesday

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

        // Should have two slaves before any checking
        assertEquals(2, computers.size());

        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, zoneId);
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
        checkRetentionStrategy(rs, computers.get(0));

        computers = Arrays.stream(r.jenkins.getComputers())
            .filter(computer -> computer instanceof EC2Computer)
            .map(computer -> (EC2Computer) computer)
            .collect(Collectors.toList());

        // Should have two slaves after check too
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
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("monday", false);
        jsonObject.put("tuesday", true);
        jsonObject.put("wednesday", false);
        jsonObject.put("thursday", false);
        jsonObject.put("friday", false);
        jsonObject.put("saturday", false);
        jsonObject.put("sunday", false);
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeDays(jsonObject);
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

        // Should have zero slaves
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
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("monday", false);
        jsonObject.put("tuesday", true);
        jsonObject.put("wednesday", false);
        jsonObject.put("thursday", false);
        jsonObject.put("friday", false);
        jsonObject.put("saturday", false);
        jsonObject.put("sunday", false);
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeDays(jsonObject);
        template.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        LocalDateTime localDateTime = LocalDateTime.of(2019, Month.SEPTEMBER, 25, 1, 0); //Wednesday

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

        // Should have two slaves before any checking
        assertEquals(2, computers.size());

        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, zoneId);
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2", clock, now.toEpochMilli() - 1);
        checkRetentionStrategy(rs, computers.get(0));

        computers = Arrays.stream(r.jenkins.getComputers())
            .filter(computer -> computer instanceof EC2Computer)
            .map(computer -> (EC2Computer) computer)
            .collect(Collectors.toList());

        // Should have two slaves after check too
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
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("monday", false);
        jsonObject.put("tuesday", true);
        jsonObject.put("wednesday", false);
        jsonObject.put("thursday", false);
        jsonObject.put("friday", false);
        jsonObject.put("saturday", false);
        jsonObject.put("sunday", false);
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeDays(jsonObject);
        template.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        //Set fixed clock to be able to test properly
        LocalDateTime localDateTime = LocalDateTime.of(2019, Month.SEPTEMBER, 24, 14, 0); //Tuesday
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

        // Should have two slaves before any checking
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

        // Should have 1 slaves after check
        assertEquals(1, computers.size());
        assertEquals(1, AmazonEC2FactoryMockImpl.instances.size());
    }

    private static void checkRetentionStrategy(EC2RetentionStrategy rs, EC2Computer c) throws InterruptedException {
        rs.check(c);
        EC2AbstractSlave node = c.getNode();
        assertTrue(node.terminateScheduled.await(10, TimeUnit.SECONDS));
    }
}
