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

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Slave;
import hudson.model.Node;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;

import net.sf.json.JSONObject;

/**
 * Slave running on EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2AbstractSlave extends Slave {
    protected String instanceId;
    
    /**
     * Comes from {@link SlaveTemplate#initScript}.
     */
    public final String initScript;
    public final String remoteAdmin; // e.g. 'ubuntu'
    public final String rootCommandPrefix; // e.g. 'sudo'
    public final String jvmopts; //e.g. -Xmx1g
    public final boolean stopOnTerminate;
    public final String idleTerminationMinutes;
    public final boolean usePrivateDnsName;
    public List<EC2Tag> tags;

    // Temporary stuff that is obtained live from EC2
    public String publicDNS;
    public String privateDNS;
    
    // Whether or not Jenkins should connect to the instance via SSH
    // Spot slaves register as a headless slave
    protected boolean connectViaSsh = true;

    /* The last instance data to be fetched for the slave */
    protected Instance lastFetchInstance = null;

    /* The time at which we fetched the last instance data */
    protected long lastFetchTime = 0;

    /* The time (in milliseconds) after which we will always re-fetch externally changeable EC2 data when we are asked for it */
    protected static final long MIN_FETCH_TIME = 20 * 1000;


    /**
     * For data read from old Hudson, this is 0, so we use that to indicate 22.
     */
    protected final int sshPort;

    public static final String TEST_ZONE = "testZone";
    
    /*
     * Used to determine if the slave is On Demand or Spot 
     */
    protected String ec2Type;

    @DataBoundConstructor
    public EC2AbstractSlave(String name, String instanceId, String description, String remoteFS, int sshPort, int numExecutors, Mode mode, String labelString, ComputerLauncher launcher, RetentionStrategy retentionStrategy, String initScript, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, List<EC2Tag> tags, boolean usePrivateDnsName) throws FormException, IOException {

        super(name, "", remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);

        this.instanceId = instanceId;
        this.initScript  = initScript;
        this.remoteAdmin = remoteAdmin;
        this.rootCommandPrefix = rootCommandPrefix;
        this.jvmopts = jvmopts;
        this.sshPort = sshPort;
        this.stopOnTerminate = stopOnTerminate;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.tags = tags;
        this.usePrivateDnsName = usePrivateDnsName;
    }

    protected Object readResolve() {
	/*
	 * If instanceId is null, this object was deserialized from an old
	 * version of the plugin, where this field did not exist (prior to
	 * version 1.18). In those versions, the node name *was* the instance
	 * ID, so we can get it from there.
	 */
	if (instanceId == null) {
	    instanceId = getNodeName();
	}

	return this;
    }

    /**
     * See http://aws.amazon.com/ec2/instance-types/
     */
    /*package*/ static int toNumExecutors(InstanceType it) {
        switch (it) {
        case T1Micro:       return 1;
        case M1Small:       return 1;
        case M1Medium:      return 2;
        case M1Large:       return 4;
        case C1Medium:      return 5;
        case M2Xlarge:      return 6;
        case M1Xlarge:      return 8;
        case M22xlarge:     return 13;
        case M3Xlarge:      return 13;
        case C1Xlarge:      return 20;
        case M24xlarge:     return 26;
        case M32xlarge:     return 26;
        case Cc14xlarge:    return 33;
        case Cg14xlarge:    return 33;
        default:            throw new AssertionError();
        }
    }

    /**
     * EC2 instance ID.
     */
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public Computer createComputer() {
        return new EC2Computer(this);
    }

    public static Instance getInstance(String instanceId) {
        DescribeInstancesRequest request = new DescribeInstancesRequest();
    	request.setInstanceIds(Collections.<String>singletonList(instanceId));
        EC2Cloud cloudInstance = EC2Cloud.get();
        if (cloudInstance == null)
        	return null;
        AmazonEC2 ec2 = cloudInstance.connect();
    	List<Reservation> reservations = ec2.describeInstances(request).getReservations();
        Instance i = null;
    	if (reservations.size() > 0) {
    		List<Instance> instances = reservations.get(0).getInstances();
    		if (instances.size() > 0)
    			i = instances.get(0);
    	}
    	return i;
    }

    /**
     * Terminates the instance in EC2.
     */
    public abstract void terminate();
    
    void stop() {
        try {
            AmazonEC2 ec2 = EC2Cloud.get().connect();
            StopInstancesRequest request = new StopInstancesRequest(
                    Collections.singletonList(getInstanceId()));
            ec2.stopInstances(request);
            LOGGER.info("EC2 instance stopped: " + getInstanceId());
            toComputer().disconnect(null);
        } catch (AmazonClientException e) {
            Instance i = getInstance(getInstanceId());
            LOGGER.log(Level.WARNING, "Failed to terminate EC2 instance: "+getInstanceId() + " info: "+((i != null)?i:"") , e);
        }
    }

    boolean terminateInstance() {
        try {
            AmazonEC2 ec2 = EC2Cloud.get().connect();
            TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(getInstanceId()));
            ec2.terminateInstances(request);
            LOGGER.info("Terminated EC2 instance (terminated): "+getInstanceId());
            return true;
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
            return false;
        }
    }

    void idleTimeout() {
	LOGGER.info("EC2 instance idle time expired: "+getInstanceId());
	if (!stopOnTerminate) {
	    terminate();
	}
	else {
	    stop();
	}
    }

    String getRemoteAdmin() {
        if (remoteAdmin == null || remoteAdmin.length() == 0)
            return "root";
        return remoteAdmin;
    }

    String getRootCommandPrefix() {
        if (rootCommandPrefix == null || rootCommandPrefix.length() == 0)
            return "";
        return rootCommandPrefix + " ";
    }

    String getJvmopts() {
        return Util.fixNull(jvmopts);
    }

    public int getSshPort() {
        return sshPort!=0 ? sshPort : 22;
    }

    public boolean getStopOnTerminate() {
        return stopOnTerminate;
    }

    protected boolean isAlive(boolean force) {
        fetchLiveInstanceData(force);
        if (lastFetchInstance == null) return false;
        if (lastFetchInstance.getState().getName().equals(InstanceStateName.Terminated.toString())) return false;
        return true;
    }

    /* Much of the EC2 data is beyond our direct control, therefore we need to refresh it from time to
       time to ensure we reflect the reality of the instances. */
    protected void fetchLiveInstanceData( boolean force ) throws AmazonClientException {
		/* If we've grabbed the data recently, don't bother getting it again unless we are forced */
        long now = System.currentTimeMillis();
        if ((lastFetchTime > 0) && (now - lastFetchTime < MIN_FETCH_TIME) && !force) {
            return;
        }

        Instance i = getInstance(getInstanceId());

        lastFetchTime = now;
        lastFetchInstance = i;
        if (i == null)
            return;

        publicDNS = i.getPublicDnsName();
        privateDNS = i.getPrivateIpAddress();
        tags = new LinkedList<EC2Tag>();

        for (Tag t : i.getTags()) {
            tags.add(new EC2Tag(t.getKey(), t.getValue()));
        }
    }

	/* Clears all existing tag data so that we can force the instance into a known state */
    protected void clearLiveInstancedata() throws AmazonClientException {
        Instance inst = getInstance(getInstanceId());

        /* Now that we have our instance, we can clear the tags on it */
        if (!tags.isEmpty()) {
            HashSet<Tag> inst_tags = new HashSet<Tag>();

            for(EC2Tag t : tags) {
                inst_tags.add(new Tag(t.getName(), t.getValue()));
            }

            DeleteTagsRequest tag_request = new DeleteTagsRequest();
            tag_request.withResources(inst.getInstanceId()).setTags(inst_tags);
            EC2Cloud.get().connect().deleteTags(tag_request);
        }
    }


    /* Sets tags on an instance.  This will not clear existing tag data, so call clearLiveInstancedata if needed */
    protected void pushLiveInstancedata() throws AmazonClientException {
        Instance inst = getInstance(getInstanceId());

        /* Now that we have our instance, we can set tags on it */
        if (tags != null && !tags.isEmpty()) {
            HashSet<Tag> inst_tags = new HashSet<Tag>();

            for(EC2Tag t : tags) {
                inst_tags.add(new Tag(t.getName(), t.getValue()));
            }

            CreateTagsRequest tag_request = new CreateTagsRequest();
            tag_request.withResources(inst.getInstanceId()).setTags(inst_tags);
            EC2Cloud.get().connect().createTags(tag_request);
        }
    }
    
    public String getPublicDNS() {
        fetchLiveInstanceData(false);
        return publicDNS;
    }

    public String getPrivateDNS() {
        fetchLiveInstanceData(false);
        return privateDNS;
    }

    public List<EC2Tag> getTags() {
        fetchLiveInstanceData(false);
        return Collections.unmodifiableList(tags);
    }

    @Override
	public Node reconfigure(final StaplerRequest req, JSONObject form) throws FormException{
        return super.reconfigure(req, form);
    }

    public boolean getUsePrivateDnsName() {
        return usePrivateDnsName;
    }

    public boolean getConnectViaSsh() {
        return connectViaSsh;
    }
    
    public static ListBoxModel fillZoneItems(String accessId, String secretKey, String region) throws IOException, ServletException {
		ListBoxModel model = new ListBoxModel();
		if (AmazonEC2Cloud.testMode) {
			model.add(TEST_ZONE);
			return model;
		}

		if (!StringUtils.isEmpty(accessId) && !StringUtils.isEmpty(secretKey) && !StringUtils.isEmpty(region)) {
			AmazonEC2 client = EC2Cloud.connect(accessId, secretKey, AmazonEC2Cloud.getEc2EndpointUrl(region));
			DescribeAvailabilityZonesResult zones = client.describeAvailabilityZones();
			List<AvailabilityZone> zoneList = zones.getAvailabilityZones();
			model.add("<not specified>", "");
			for (AvailabilityZone z : zoneList) {
				model.add(z.getZoneName(), z.getZoneName());
			}
		}
		return model;
	}
    
    public String getEc2Type() {
    	return ec2Type;
    }


    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        @Override
		public String getDisplayName() {
            return "Amazon EC2";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }

        public ListBoxModel doFillZoneItems(@QueryParameter String accessId,
        		@QueryParameter String secretKey, @QueryParameter String region) throws IOException,
    			ServletException {
        	return fillZoneItems(accessId, secretKey, region);
    	}
    }

    private static final Logger LOGGER = Logger.getLogger(EC2AbstractSlave.class.getName());
}
