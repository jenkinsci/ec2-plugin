package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import hudson.slaves.NodeProperty;
import jenkins.model.Jenkins;
import org.jvnet.hudson.test.HudsonTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class EC2RetentionStrategyTest extends HudsonTestCase {

    final AtomicBoolean idleTimeoutCalled = new AtomicBoolean(false);

    public void testOnBillingHourRetention() throws Exception {
        EC2RetentionStrategy rs = new EC2RetentionStrategy("-2");
        List<int[]> upTime = new ArrayList<int[]>();
        List<Boolean> expected = new ArrayList<Boolean>();
        upTime.add(new int[]{58,0});
        expected.add(true);
        upTime.add(new int[]{57,59});
        expected.add(false);
        upTime.add(new int[]{59,00});
        expected.add(true);
        upTime.add(new int[]{59,30});
        expected.add(true);
        upTime.add(new int[]{60,00});
        expected.add(false);

        for (int i = 0; i < upTime.size(); i++) {
            int[] t = upTime.get(i);
            EC2Computer computer = computerWithIdleTime(t[0], t[1]);
            rs.check(computer);
            assertEquals("Expected " + t[0] + "m" + t[1] + "s to be " + expected.get(i), (boolean)expected.get(i), idleTimeoutCalled.get());
            // reset the assumption
            idleTimeoutCalled.set(false);
        }
    }

    private EC2Computer computerWithIdleTime(final int minutes, final int seconds) throws Exception {
        final EC2AbstractSlave slave = new EC2AbstractSlave("name","id","description","fs",22,1,null,"label",null,null,"init", new ArrayList<NodeProperty<?>>(),"remote","root","jvm",false,"idle",null,"cloud",false,Integer.MAX_VALUE ) {
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
                return ((minutes * 60L) + seconds)  * 1000L;
            }

            @Override
            public boolean isOffline() {
                return false;
            }
        };
        assertTrue(computer.isIdle());
        assertTrue(computer.isOnline());
        return computer;
    }
}
