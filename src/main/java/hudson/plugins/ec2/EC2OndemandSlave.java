package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.NodeProperty;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

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
public final class EC2OndemandSlave extends EC2AbstractSlave {
	
    public EC2OndemandSlave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors, String labelString, Mode mode, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags) throws FormException, IOException {
    	this(instanceId, description, remoteFS, sshPort, numExecutors, labelString, Mode.NORMAL, initScript, Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate, idleTerminationMinutes, publicDNS, privateDNS, tags, false);
    }
    
    public EC2OndemandSlave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors, String labelString, Mode mode, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags, boolean usePrivateDnsName) throws FormException, IOException {
    	this(instanceId, description, remoteFS, sshPort, numExecutors, labelString, Mode.NORMAL, initScript, Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate, idleTerminationMinutes, publicDNS, privateDNS, tags, usePrivateDnsName);
    } 	 

    @DataBoundConstructor
    public EC2OndemandSlave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors, String labelString, Mode mode, String initScript, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags, boolean usePrivateDnsName) throws FormException, IOException {	
    	
        super(description + " (" + instanceId + ")", instanceId, description, remoteFS, sshPort, numExecutors, mode, labelString, new EC2UnixLauncher(), new EC2RetentionStrategy(idleTerminationMinutes), initScript, nodeProperties, remoteAdmin, rootCommandPrefix, jvmopts, false, idleTerminationMinutes, tags, usePrivateDnsName);

        this.publicDNS = publicDNS;
        this.privateDNS = privateDNS;
        this.ec2Type = "On Demand";
    }

    /**
     * Constructor for debugging.
     */
    public EC2OndemandSlave(String instanceId) throws FormException, IOException {
        this(instanceId,"debug", "/tmp/hudson", 22, 1, "debug", Mode.NORMAL, "", Collections.<NodeProperty<?>>emptyList(), null, null, null, false, null, "Fake public", "Fake private", null, false);
    }

    
    /**
     * Terminates the instance in EC2.
     */
    public void terminate() {
        try {
            if (!isAlive(true)) {
                /* The node has been killed externally, so we've nothing to do here */
                LOGGER.info("EC2 instance already terminated: "+getInstanceId());
            } else {
                AmazonEC2 ec2 = EC2Cloud.get().connect();
                TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(getInstanceId()));
                ec2.terminateInstances(request);
                LOGGER.info("Terminated EC2 instance (terminated): "+getInstanceId());
            }
            Hudson.getInstance().removeNode(this);
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        }
    }

    @Override
	public Node reconfigure(final StaplerRequest req, JSONObject form) throws FormException {
        if (form == null) {
            return null;
        }

        if (!isAlive(true)) {
            LOGGER.info("EC2 instance terminated externally: " + getInstanceId());
            try {
                Hudson.getInstance().removeNode(this);
            } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Attempt to reconfigure EC2 instance which has been externally terminated: " + getInstanceId(), ioe);
            }

            return null;
        }

        Node result = reconfigure(req, form);

        /* Get rid of the old tags, as represented by ourselves. */
        clearLiveInstancedata();

        /* Set the new tags, as represented by our successor */
        ((EC2OndemandSlave) result).pushLiveInstancedata();

        return result;
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

    private static final Logger LOGGER = Logger.getLogger(EC2OndemandSlave.class.getName());
}
