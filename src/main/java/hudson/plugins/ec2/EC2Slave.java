package hudson.plugins.ec2;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.Reservation;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.util.ListBoxModel;

public abstract class EC2Slave extends Slave {

	public final String remoteAdmin; // e.g. 'ubuntu'
	public final String rootCommandPrefix; // e.g. 'sudo'
	public final String jvmopts; //e.g. -Xmx1g
	public final boolean stopOnTerminate;
	public final String idleTerminationMinutes;
	public List<EC2Tag> tags;
	protected boolean connectOnStartup;

    public static final String TEST_ZONE = "testZone";

	/* The last instance data to be fetched for the slave */
	protected Instance lastFetchInstance = null;

	/* The time at which we fetched the last instance data */
	protected long lastFetchTime = 0;

	/* The time (in milliseconds) after which we will always re-fetch externally changeable EC2 data when we are asked for it */
	protected static final long MIN_FETCH_TIME = 20 * 1000;


	public EC2Slave(String instanceId, String description, String remoteFS,
			int numExecutors, Mode mode, String labelString, 
			ComputerLauncher launcher, EC2RetentionStrategy retentionStrategy, List<? extends NodeProperty<?>> nodeProperties,
			String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate,
			String idleTerminationMinutes, List<EC2Tag> tags) throws FormException, IOException {

		super(instanceId, description, remoteFS, numExecutors, mode, labelString, 
				launcher, retentionStrategy, nodeProperties);


		this.remoteAdmin = remoteAdmin;
		this.rootCommandPrefix = rootCommandPrefix;
		this.jvmopts = jvmopts;
		this.stopOnTerminate = stopOnTerminate;
		this.idleTerminationMinutes = idleTerminationMinutes;
		this.tags = tags;
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
		case C1Xlarge:      return 20;
		case M24xlarge:     return 26;
		case Cc14xlarge:    return 33;
		case Cg14xlarge:    return 33;
		default:            throw new AssertionError();
		}
	}
	
	/**
     * EC2 instance ID (on demand) or spot request id (spot)
     */
    public abstract String getInstanceId();
    
    public boolean getConnectOnStartup(){
    	return connectOnStartup;
    }
    
    @Override
	public Computer createComputer() {
		return new EC2Computer(this);
	}
    
    /**
     * Terminates the EC2 instance associated with the slave
     * and removes the node from Jenkins
     */
    public abstract void terminate();

    /**
     * Fired when the specified idle timeout is reached
     */
    abstract void idleTimeout();
    
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
    
    /**
     * Whether or not this slave gets stopped instead of terminated
     * @return
     */
    public boolean getStopOnTerminate() {
        return stopOnTerminate;
    }
    
    /**
     * Get the SSH port for a node
     * Default to 0. Should be overridden by subclasses
     * @return 0
     */
	public int getSshPort() {
		return 0;
	}
    
	/**
	 * Get the EC2 instance from the instance id
	 * @param instanceId - InstanceID for the EC2 node
	 * @return Instance with specified instanceId, or null
	 */
    public static Instance getInstance(String instanceId) {
    	if (instanceId == null || instanceId.trim().equals("")) return null;
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
    
    /* Much of the EC2 data is beyond our direct control, therefore we need to refresh it from time to
    time to ensure we reflect the reality of the instances. */
    protected abstract void fetchLiveInstanceData(boolean force);
    
    /**
     * Retrieve the stored tags 
     * @return immutable list of tags
     */
    public List<EC2Tag> getTags() {
        fetchLiveInstanceData(false);
        return Collections.unmodifiableList(tags);
    }
    
    @Override
    public Node reconfigure(final StaplerRequest req, JSONObject form) throws FormException{
    	return super.reconfigure(req, form);
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
}
