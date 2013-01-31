package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Slave.SlaveDescriptor;
import hudson.model.Hudson;
import hudson.model.Slave;
import hudson.model.Node;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.NodeProperty;
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
public class EC2OndemandSlave extends EC2Slave {
    
	/**
	 * Comes from {@link SlaveTemplate#initScript}.
	 */
    public final String initScript;
    public final String instanceId;
    public final boolean usePrivateDnsName;
    
    // Temporary stuff that is obtained live from EC2
    public String publicDNS;
    public String privateDNS;

    /**
     * For data read from old Hudson, this is 0, so we use that to indicate 22.
     */
    private final int sshPort;
    
    public EC2OndemandSlave(String name, String instanceId, String description, String remoteFS, int sshPort, int numExecutors, String labelString, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags) throws FormException, IOException {
        this(name, instanceId, description, remoteFS, sshPort, numExecutors, Mode.NORMAL, labelString, initScript, Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate, idleTerminationMinutes, publicDNS, privateDNS, tags, false);
    }

    public EC2OndemandSlave(String name, String instanceId, String description, String remoteFS, int sshPort, int numExecutors, String labelString, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags, boolean usePrivateDnsName) throws FormException, IOException {
        this(name, instanceId, description, remoteFS, sshPort, numExecutors, Mode.NORMAL, labelString, initScript, Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate, idleTerminationMinutes, publicDNS, privateDNS, tags, usePrivateDnsName);
    }


    @DataBoundConstructor
    public EC2OndemandSlave(String name, String instanceId, String description, String remoteFS, int sshPort, int numExecutors, Mode mode, String labelString, String initScript, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags, boolean usePrivateDnsName) throws FormException, IOException {

        super(name, description, remoteFS, numExecutors, mode, labelString, new EC2UnixLauncher(), new EC2RetentionStrategy(idleTerminationMinutes), nodeProperties, remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate, idleTerminationMinutes, tags);

        this.instanceId = instanceId;
        this.initScript  = initScript;
        this.sshPort = sshPort;
        this.publicDNS = publicDNS;
        this.privateDNS = privateDNS;
        this.usePrivateDnsName = usePrivateDnsName;
        connectOnStartup = true;
    }

    /**
     * Constructor for debugging.
     */
    public EC2OndemandSlave(String instanceId) throws FormException, IOException {
        this(instanceId, instanceId,"debug", "/tmp/hudson", 22, 1, Mode.NORMAL, "debug", "", Collections.<NodeProperty<?>>emptyList(), null, null, null, false, null, "Fake public", "Fake private", null, false);
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

	void idleTimeout() {
		LOGGER.info("EC2 instance idle time expired: "+getInstanceId());
		if (!stopOnTerminate) {
			terminate();
			return;
		}

		try {
			AmazonEC2 ec2 = EC2Cloud.get().connect();
			StopInstancesRequest request = new StopInstancesRequest(
					Collections.singletonList(getInstanceId()));
			ec2.stopInstances(request);
			toComputer().disconnect(null);
		} catch (AmazonClientException e) {
	        Instance i = getInstance(getNodeName());
			LOGGER.log(Level.WARNING, "Failed to terminate EC2 instance: "+getInstanceId() + " info: "+((i != null)?i:"") , e);
		}
		LOGGER.info("EC2 instance stopped: " + getInstanceId());
	}

	@Override
    public int getSshPort() {
        return sshPort!=0 ? sshPort : 22;
    }

	public String getInstanceId(){
		return instanceId;
	}
	
    private boolean isAlive(boolean force) {
        fetchLiveInstanceData(force);
        if (lastFetchInstance == null) return false;
        if (lastFetchInstance.getState().getName().equals(InstanceStateName.Terminated.toString())) return false;
        return true;
    }

    
    protected void fetchLiveInstanceData( boolean force ) throws AmazonClientException {
		/* If we've grabbed the data recently, don't bother getting it again unless we are forced */
        long now = System.currentTimeMillis();
        if ((lastFetchTime > 0) && (now - lastFetchTime < MIN_FETCH_TIME) && !force) {
            return;
        }

        Instance i = getInstance(getNodeName());

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
    private void clearLiveInstancedata() throws AmazonClientException {
        Instance inst = getInstance(getNodeName());

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
    private void pushLiveInstancedata() throws AmazonClientException {
        Instance inst = getInstance(getNodeName());

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

        Node result = super.reconfigure(req, form);

        /* Get rid of the old tags, as represented by ourselves. */
        clearLiveInstancedata();

        /* Set the new tags, as represented by our successor */
        ((EC2OndemandSlave) result).pushLiveInstancedata();

        return result;
    }


    public boolean getUsePrivateDnsName() {
        return usePrivateDnsName;
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
