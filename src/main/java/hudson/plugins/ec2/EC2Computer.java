/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import hudson.Util;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.util.Collections;

import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
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
        if (getNode() != null)
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
