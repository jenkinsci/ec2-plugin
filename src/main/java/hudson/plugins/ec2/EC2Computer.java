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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Node;
import hudson.slaves.SlaveComputer;
import java.io.IOException;


import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
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

    public EC2Computer(EC2AbstractSlave slave) {
        super(slave);
    }

    @Override
    public EC2AbstractSlave getNode() {
        return (EC2AbstractSlave) super.getNode();
    }

    @CheckForNull
    public String getInstanceId() {
        EC2AbstractSlave node = (EC2AbstractSlave) super.getNode();
        if (node == null) {
            return null;
        }
        return node.getInstanceId();
    }

    public String getEc2Type() {
        return getNode().getEc2Type();
    }

    public String getSpotInstanceRequestId() {
        if (getNode() instanceof EC2SpotSlave) {
            return ((EC2SpotSlave) getNode()).getSpotInstanceRequestId();
        }
        return "";
    }

    public EC2Cloud getCloud() {
        EC2AbstractSlave node = getNode();
        if (node == null)
            return null;
        return node.getCloud();
    }

    public SlaveTemplate getSlaveTemplate() {
        return getCloud().getTemplate(getNode().templateDescription);
    }

    /**
     * Gets the EC2 console output.
     */
    public String getConsoleOutput() throws AmazonClientException {
        AmazonEC2 ec2 = getCloud().connect();
        GetConsoleOutputRequest request = new GetConsoleOutputRequest(getInstanceId());
        return ec2.getConsoleOutput(request).getOutput();
    }

    /**
     * Obtains the instance state description in EC2.
     *
     * <p>
     * This method returns a cached state, so it's not suitable to check {@link Instance#getState()} from the returned
     * instance (but all the other fields are valid as it won't change.)
     *
     * The cache can be flushed using {@link #updateInstanceDescription()}
     */
    public Instance describeInstance() throws AmazonClientException, InterruptedException {
        if (ec2InstanceDescription == null)
            ec2InstanceDescription = CloudHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        return ec2InstanceDescription;
    }

    /**
     * This will flush any cached description held by {@link #describeInstance()}.
     */
    public Instance updateInstanceDescription() throws AmazonClientException, InterruptedException {
        return ec2InstanceDescription = CloudHelper.getInstanceWithRetry(getInstanceId(), getCloud());
    }

    /**
     * Gets the current state of the instance.
     *
     * <p>
     * Unlike {@link #describeInstance()}, this method always return the current status by calling EC2.
     */
    public InstanceState getState() throws AmazonClientException, InterruptedException {
        ec2InstanceDescription = CloudHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        return InstanceState.find(ec2InstanceDescription.getState().getName());
    }

    /**
     * Number of milli-secs since the instance was started.
     */
    public long getUptime() throws AmazonClientException, InterruptedException {
        return System.currentTimeMillis() - describeInstance().getLaunchTime().getTime();
    }

    /**
     * Returns uptime in the human readable form.
     */
    public String getUptimeString() throws AmazonClientException, InterruptedException {
        return Util.getTimeSpanString(getUptime());
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

    /**
     * What username to use to run root-like commands
     *
     * @return remote admin or {@code null} if the associated {@link Node} is {@code null}
     */
    @CheckForNull
    public String getRemoteAdmin() {
        EC2AbstractSlave node = getNode();
        return node == null ? null : node.getRemoteAdmin();
    }

    public int getSshPort() {
        return getNode().getSshPort();
    }

    public String getRootCommandPrefix() {
        return getNode().getRootCommandPrefix();
    }

    public String getSlaveCommandPrefix() {
        return getNode().getSlaveCommandPrefix();
    }

    public void onConnected() {
        EC2AbstractSlave node = getNode();
        if (node != null) {
            node.onConnected();
        }
    }

}
