package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Util;
import hudson.model.Slave.SlaveDescriptor;
import hudson.slaves.SlaveComputer;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.Collections;

import javax.servlet.ServletException;

import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.GetConsoleOutputRequest;
import com.amazonaws.services.ec2.model.Instance;

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
    public String getConsoleOutput() throws AmazonClientException {
        AmazonEC2 ec2 = EC2Cloud.get().connect();
        GetConsoleOutputRequest request = new GetConsoleOutputRequest(getInstanceId());
        return ec2.getConsoleOutput(request).getOutput();
    }

    /**
     * Obtains the instance state description in EC2.
     *
     * <p>
     * This method returns a cached state, so it's not suitable to check {@link Instance#getState()}
     * and {@link Instance#getStateCode()} from the returned instance (but all the other fields are valid as it won't change.)
     *
     * The cache can be flushed using {@link #updateInstanceDescription()}
     */
    public Instance describeInstance() throws AmazonClientException {
        if(ec2InstanceDescription==null)
            ec2InstanceDescription = _describeInstance();
        return ec2InstanceDescription;
    }

    /**
     * This will flush any cached description held by {@link #describeInstance()}.
     */
    public Instance updateInstanceDescription() throws AmazonClientException {
        return ec2InstanceDescription = _describeInstance();
    }

    /**
     * Gets the current state of the instance.
     *
     * <p>
     * Unlike {@link #describeInstance()}, this method always return the current status by calling EC2.
     */
    public InstanceState getState() throws AmazonClientException {
        ec2InstanceDescription=_describeInstance();
        return InstanceState.find(ec2InstanceDescription.getState().getName());
    }

    /**
     * Number of milli-secs since the instance was started.
     */
    public long getUptime() throws AmazonClientException {
        return System.currentTimeMillis()-describeInstance().getLaunchTime().getTime();
    }

    /**
     * Returns uptime in the human readable form.
     */
    public String getUptimeString() throws AmazonClientException {
        return Util.getTimeSpanString(getUptime());
    }

    private Instance _describeInstance() throws AmazonClientException {
    	DescribeInstancesRequest request = new DescribeInstancesRequest();
    	request.setInstanceIds(Collections.<String>singletonList(getNode().getInstanceId()));
        return EC2Cloud.get().connect().describeInstances(request).getReservations().get(0).getInstances().get(0);
    }

    /**
     * When the slave is deleted, terminate the instance.
     */
    @Override
    public HttpResponse doDoDelete() throws IOException {
        checkPermission(DELETE);
        getNode().terminate();
        return new HttpRedirect("..");
    }

    /** What username to use to run root-like commands
     *
     */
    public String getRemoteAdmin() {
        return getNode().getRemoteAdmin();
    }

    public int getSshPort() {
         return getNode().getSshPort();
     }

    public String getRootCommandPrefix() {
        return getNode().getRootCommandPrefix();
    }
    
}
