package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.ReservationDescription;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;
import hudson.Util;
import hudson.slaves.SlaveComputer;
import org.kohsuke.stapler.StaplerResponse;

import java.io.IOException;
import java.util.Collections;

/**
 * @author Kohsuke Kawaguchi
 */
public class EC2Computer extends SlaveComputer {
    /**
     * Cached description of this EC2 instance. Lazily fetched.
     */
    private volatile Instance ec2InstanceDescription;

    public EC2Computer(EC2Slave slave) {
        super(slave);
    }

    @Override
    public EC2Slave getNode() {
        return (EC2Slave)super.getNode();
    }

    public String getInstanceId() {
        return getName();
    }

    /**
     * Gets the EC2 console output.
     */
    public String getConsoleOutput() throws EC2Exception {
        Jec2 ec2 = EC2Cloud.get().connect();
        return ec2.getConsoleOutput(getInstanceId()).getOutput();
    }

    /**
     * Obtains the instance state description in EC2.
     *
     * <p>
     * This method returns a cached state, so it's not suitable to check {@link Instance#getState()}
     * and {@link Instance#getStateCode()} from the returned instance (but all the other fields are valid as it won't change.)
     */
    public Instance describeInstance() throws EC2Exception {
        if(ec2InstanceDescription==null)
            ec2InstanceDescription = _describeInstance();
        return ec2InstanceDescription;
    }

    /**
     * Gets the current state of the instance.
     *
     * <p>
     * Unlike {@link #describeInstance()}, this method always return the current status by calling EC2.
     */
    public InstanceState getState() throws EC2Exception {
        ec2InstanceDescription=_describeInstance();
        return InstanceState.find(ec2InstanceDescription.getState());
    }

    /**
     * Number of milli-secs since the instance was started.
     */
    public long getUptime() throws EC2Exception {
        return System.currentTimeMillis()-describeInstance().getLaunchTime().getTimeInMillis();
    }

    /**
     * Returns uptime in the human readable form.
     */
    public String getUptimeString() throws EC2Exception {
        return Util.getTimeSpanString(getUptime());
    }

    private ReservationDescription.Instance _describeInstance() throws EC2Exception {
        return EC2Cloud.get().connect().describeInstances(Collections.<String>singletonList(getNode().getInstanceId())).get(0).getInstances().get(0);
    }

    /**
     * When the slave is deleted, terminate the instance.
     */
    @Override
    public void doDoDelete(StaplerResponse rsp) throws IOException {
        checkPermission(DELETE);
        getNode().terminate();
        rsp.sendRedirect("..");
    }
}
