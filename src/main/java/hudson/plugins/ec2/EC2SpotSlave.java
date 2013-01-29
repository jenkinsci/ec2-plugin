package hudson.plugins.ec2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DeleteTagsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.Tag;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Descriptor.FormException;
import hudson.model.Slave.SlaveDescriptor;
import hudson.model.Node;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.util.ListBoxModel;

public class EC2SpotSlave extends EC2Slave {
	
	private final String spotInstanceRequestId;
	
	public EC2SpotSlave(String name, String spotInstanceRequestId, String description, String remoteFS,
			int numExecutors, String labelString, String remoteAdmin, 
			String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, 
			String idleTerminationMinutes, List<EC2Tag> tags) 
					throws FormException, IOException {
		
		this(name, spotInstanceRequestId, description, remoteFS, numExecutors, Mode.NORMAL, labelString, new EC2UnixLauncher(), 
				new EC2RetentionStrategy(idleTerminationMinutes), Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate,
				idleTerminationMinutes, tags);
	}

	public EC2SpotSlave(String name, String spotInstanceRequestId, String description, String remoteFS,
			int numExecutors, Mode mode, String labelString,
			ComputerLauncher launcher, EC2RetentionStrategy retentionStrategy,
			List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin,
					String rootCommandPrefix, String jvmopts, boolean stopOnTerminate,
					String idleTerminationMinutes, List<EC2Tag> tags)
							throws FormException, IOException {
		
		super(name, description, remoteFS, numExecutors, mode, labelString,
				launcher, retentionStrategy, nodeProperties, remoteAdmin,
				rootCommandPrefix, jvmopts, stopOnTerminate, idleTerminationMinutes,
				tags);
		
		connectOnStartup = false;
		this.spotInstanceRequestId = spotInstanceRequestId;
	}


	@Override
	public void terminate() {
		// Cancel the spot request
		AmazonEC2 ec2 = getEc2Cloud();
		if (ec2 == null) return;
		
		List<String> requestIds = new ArrayList<String>();
		requestIds.add(spotInstanceRequestId);
		CancelSpotInstanceRequestsRequest cancelRequest = new CancelSpotInstanceRequestsRequest(requestIds);
		
	    ec2.cancelSpotInstanceRequests(cancelRequest);
		
		
		// TODO: Terminate the slave if it is running

	}

	@Override
	void idleTimeout() {
		// TODO Auto-generated method stub

	}
	
	private AmazonEC2 getEc2Cloud(){
		EC2Cloud cloudInstance = EC2Cloud.get();
		if (cloudInstance == null) return null;
		return cloudInstance.connect();
	}
	
	private SpotInstanceRequest getSpotRequest(String requestId){
		AmazonEC2 ec2 = getEc2Cloud();
		if(ec2 == null) return null;
		
		DescribeSpotInstanceRequestsRequest dsirRequest = new DescribeSpotInstanceRequestsRequest().withSpotInstanceRequestIds(requestId);
		DescribeSpotInstanceRequestsResult dsirResult = ec2.describeSpotInstanceRequests(dsirRequest);
		List<SpotInstanceRequest> siRequests = dsirResult.getSpotInstanceRequests();
		if (siRequests.size() <= 0) return null;
		return siRequests.get(0);
	}

	private Instance getInstance(SpotInstanceRequest sir){
		return getInstance(sir.getInstanceId());
	}
	
	@Override
	protected void fetchLiveInstanceData(boolean force) {
		/* If we've grabbed the data recently, don't bother getting it again unless we are forced */
        long now = System.currentTimeMillis();
        if ((lastFetchTime > 0) && (now - lastFetchTime < MIN_FETCH_TIME) && !force) {
            return;
        }

        SpotInstanceRequest sir = getSpotRequest(spotInstanceRequestId);
        Instance i = getInstance(sir);

        lastFetchTime = now;
        lastFetchInstance = i;
        if (i == null)
        	return;

        tags = new LinkedList<EC2Tag>();

        for (Tag t : i.getTags()) {
            tags.add(new EC2Tag(t.getKey(), t.getValue()));
        }

	}
	
	private boolean isAlive(boolean force){
        fetchLiveInstanceData(force);
        if (lastFetchInstance == null) return false;
        if (lastFetchInstance.getState().getName().equals(InstanceStateName.Terminated.toString())) return false;
        
		return true;
	}

	@Override
	public Node reconfigure(StaplerRequest req, JSONObject form)
			throws FormException {

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
        ((EC2SpotSlave) result).pushLiveInstancedata();

        return result;
	}
	
	
	/* Clears all existing tag data so that we can force the instance into a known state */
    private void clearLiveInstancedata() throws AmazonClientException {
        Instance inst = getInstance(getSpotRequest(spotInstanceRequestId));
        if (inst == null) return;

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
    	Instance inst = getInstance(this.getSpotRequest(spotInstanceRequestId));
        if (inst == null) return;

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

	private static final Logger LOGGER = Logger.getLogger(EC2SpotSlave.class.getName());


}
