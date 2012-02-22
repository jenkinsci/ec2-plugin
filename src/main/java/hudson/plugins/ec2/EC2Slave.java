package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Slave;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.NodeProperty;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

/**
 * Slave running on EC2.
 * 
 * @author Kohsuke Kawaguchi
 */
public final class EC2Slave extends Slave {
    /**
     * Comes from {@link SlaveTemplate#initScript}.
     */
    public final String zone;
    public final String initScript;
    public final String remoteAdmin; // e.g. 'ubuntu'
    public final String rootCommandPrefix; // e.g. 'sudo'
    public final String jvmopts; //e.g. -Xmx1g

    /**
     * For data read from old Hudson, this is 0, so we use that to indicate 22.
     */
    private final int sshPort;

    public static final String TEST_ZONE = "testZone";
    
    public EC2Slave(String instanceId, String description, String zone, String remoteFS, int sshPort, int numExecutors, String labelString, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts) throws FormException, IOException {
        this(instanceId, description, zone, remoteFS, sshPort, numExecutors, Mode.NORMAL, labelString, initScript, Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts);
    }

    @DataBoundConstructor
    public EC2Slave(String instanceId, String description, String zone, String remoteFS, int sshPort, int numExecutors, Mode mode, String labelString, String initScript, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String rootCommandPrefix, String jvmopts) throws FormException, IOException {
        super(instanceId, description, remoteFS, numExecutors, mode, labelString, new EC2UnixLauncher(), new EC2RetentionStrategy(), nodeProperties);
        this.zone  = zone;
        this.initScript  = initScript;
        this.remoteAdmin = remoteAdmin;
        this.rootCommandPrefix = rootCommandPrefix;
        this.jvmopts = jvmopts;
        this.sshPort = sshPort;
    }

    /**
     * Constructor for debugging.
     */
    public EC2Slave(String instanceId) throws FormException, IOException {
        this(instanceId,"debug","zone", "/tmp/hudson", 22, 1, Mode.NORMAL, "debug", "", Collections.<NodeProperty<?>>emptyList(), null, null, null);
    }

    /**
     * See http://aws.amazon.com/ec2/instance-types/
     */
    /*package*/ static int toNumExecutors(InstanceType it) {
        switch (it) {
        case T1Micro:       return 1;
        case M1Small:       return 1;
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
    public Computer createComputer() {
        return new EC2Computer(this);
    }

    /**
     * Terminates the instance in EC2.
     */
    public void terminate() {
        try {
            AmazonEC2 ec2 = EC2Cloud.get().connect();
            TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(getInstanceId()));
            ec2.terminateInstances(request);
            LOGGER.info("Terminated EC2 instance: "+getInstanceId());
            Hudson.getInstance().removeNode(this);
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        }
    }

    String getZone() {
        return zone;
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

	public static ListBoxModel fillZoneItems(String accessId,
			String secretKey, String region) throws IOException,
			ServletException {
		ListBoxModel model = new ListBoxModel();
		if (AmazonEC2Cloud.testMode) {
			model.add(TEST_ZONE);
			return model;
		}
			
		if (!StringUtils.isEmpty(accessId) && !StringUtils.isEmpty(secretKey) && !StringUtils.isEmpty(region)) {
			AmazonEC2 client = AmazonEC2Cloud.connect(accessId, secretKey, AmazonEC2Cloud.getEc2EndpointUrl(region));
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

    private static final Logger LOGGER = Logger.getLogger(EC2Slave.class.getName());
}
