package hudson.plugins.ec2;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.StaplerRequest;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;

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
     * EC2 instance ID.
     */
    public String getInstanceId() {
        return getNodeName();
    }
    
    @Override
    public abstract Computer createComputer();
    
    public abstract void terminate();

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
    
    public boolean getStopOnTerminate() {
        return stopOnTerminate;
    }
    
    protected abstract void fetchLiveInstanceData(boolean force);
    
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
}
