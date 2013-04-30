package hudson.plugins.ec2;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

import hudson.Extension;
import hudson.model.Hudson;
import hudson.model.Descriptor.FormException;
import hudson.slaves.NodeProperty;
import hudson.util.ListBoxModel;

public final class EC2SpotSlave extends EC2AbstractSlave {

	private final String spotInstanceRequestId;

	public EC2SpotSlave(String name, String spotInstanceRequestId, String description, String remoteFS, int sshPort, int numExecutors, Mode mode, String initScript, String labelString, String remoteAdmin, String rootCommandPrefix, String jvmopts, String idleTerminationMinutes, List<EC2Tag> tags, EC2Cloud cloud, boolean usePrivateDnsName) throws FormException, IOException {
		this(name, spotInstanceRequestId, description, remoteFS, sshPort, numExecutors, mode, initScript, labelString, Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, idleTerminationMinutes, tags, cloud, usePrivateDnsName);
	}

	@DataBoundConstructor
	public EC2SpotSlave(String name, String spotInstanceRequestId, String description, String remoteFS, int sshPort, int numExecutors, Mode mode, String initScript, String labelString, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String rootCommandPrefix, String jvmopts, String idleTerminationMinutes, List<EC2Tag> tags, EC2Cloud cloud, boolean usePrivateDnsName) throws FormException, IOException {

		super(name, "", description, remoteFS, sshPort, numExecutors, mode, labelString, new EC2SpotComputerLauncher(), new EC2SpotRetentionStrategy(idleTerminationMinutes), initScript, nodeProperties, remoteAdmin, rootCommandPrefix, jvmopts, false, idleTerminationMinutes, tags, cloud, usePrivateDnsName);

		this.name = name;
		this.spotInstanceRequestId = spotInstanceRequestId;
	}


	/**
	 * Cancel the spot request for the instance.
	 * Terminate the instance if it is up.
	 * Remove the slave from Jenkins.
	 */
	@Override
	public void terminate() {
		// Cancel the spot request
		AmazonEC2 ec2 = cloud.connect();

		String instanceId = getInstanceId();
		List<String> requestIds = Collections.singletonList(spotInstanceRequestId);
		CancelSpotInstanceRequestsRequest cancelRequest = new CancelSpotInstanceRequestsRequest(requestIds);
		try{
			ec2.cancelSpotInstanceRequests(cancelRequest);
			LOGGER.info("Canceled Spot request: "+ spotInstanceRequestId);

			// Terminate the slave if it is running
			if (instanceId != null && !instanceId.equals("")){
				if (!isAlive(true)) {
					/* The node has been killed externally, so we've nothing to do here */
					LOGGER.info("EC2 instance already terminated: "+instanceId);
				} else{
					TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(instanceId));
					ec2.terminateInstances(request);
					LOGGER.info("Terminated EC2 instance (terminated): "+instanceId);
				}

			}
			Hudson.getInstance().removeNode(this);
			
		} catch (AmazonServiceException e){
			// Spot request is no longer valid
			LOGGER.log(Level.WARNING, "Failed to terminated instance and cancel Spot request: " + spotInstanceRequestId);
		} catch (AmazonClientException e){
			// Spot request is no longer valid
			LOGGER.log(Level.WARNING, "Failed to terminated instance and cancel Spot request: " + spotInstanceRequestId);
		} catch(IOException e){
			LOGGER.log(Level.WARNING,"Failed to remove slave: "+name, e);
		}

	}

	/**
	 * Retrieve the SpotRequest for a requestId
	 * @param requestId
	 * @return SpotInstanceRequest object for the requestId, or null
	 */
	private SpotInstanceRequest getSpotRequest(String spotRequestId){
		AmazonEC2 ec2 = cloud.connect();

		DescribeSpotInstanceRequestsRequest dsirRequest = new DescribeSpotInstanceRequestsRequest().withSpotInstanceRequestIds(spotRequestId);
		DescribeSpotInstanceRequestsResult dsirResult = ec2.describeSpotInstanceRequests(dsirRequest);
		List<SpotInstanceRequest> siRequests = dsirResult.getSpotInstanceRequests();
		if (siRequests.size() <= 0) return null;
		return siRequests.get(0);
	}

	/**
	 * Accessor for the spotInstanceRequestId
	 */
	public String getSpotInstanceRequestId(){
		return spotInstanceRequestId;
	}

	@Override
	public String getInstanceId() {
		if (instanceId == null || instanceId.equals("")){
			this.instanceId = this.getSpotRequest(spotInstanceRequestId).getInstanceId();
		}
		return instanceId;
	}

	@Extension
	public static final class DescriptorImpl extends SlaveDescriptor {
		@Override
		public String getDisplayName() {
			return "Amazon EC2 Spot Instance";
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

	private static final Logger LOGGER = Logger.getLogger(EC2SpotSlave.class.getName());

	@Override
	public String getEc2Type() {
		String spotMaxBidPrice = this.getSpotRequest(spotInstanceRequestId).getSpotPrice();
		return "Spot - $" + spotMaxBidPrice.substring(0, spotMaxBidPrice.length() - 3) + " max bid price";
	}


}
