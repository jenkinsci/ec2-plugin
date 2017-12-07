package hudson.plugins.ec2;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotInstanceState;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.plugins.ec2.win.EC2WindowsLauncher;
import hudson.slaves.NodeProperty;

import javax.annotation.CheckForNull;

public final class EC2SpotSlave extends EC2AbstractSlave {
    private static final Logger LOGGER = Logger.getLogger(EC2SpotSlave.class.getName());

    private final String spotInstanceRequestId;

    public EC2SpotSlave(String name, String spotInstanceRequestId, String description, String remoteFS, int numExecutors, Mode mode, String initScript, String tmpDir, String labelString, String remoteAdmin, String jvmopts, String idleTerminationMinutes, List<EC2Tag> tags, String cloudName, boolean usePrivateDnsName, int launchTimeout, AMITypeData amiType)
            throws FormException, IOException {
        this(description + " (" + name + ")", spotInstanceRequestId, description, remoteFS, numExecutors, mode, initScript, tmpDir, labelString, Collections.<NodeProperty<?>> emptyList(), remoteAdmin, jvmopts, idleTerminationMinutes, tags, cloudName, usePrivateDnsName, launchTimeout, amiType);
    }

    @DataBoundConstructor
    public EC2SpotSlave(String name, String spotInstanceRequestId, String description, String remoteFS, int numExecutors, Mode mode, String initScript, String tmpDir, String labelString, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String jvmopts, String idleTerminationMinutes, List<EC2Tag> tags, String cloudName, boolean usePrivateDnsName, int launchTimeout, AMITypeData amiType)
            throws FormException, IOException {

        super(name, "", description, remoteFS, numExecutors, mode, labelString, amiType.isWindows() ? new EC2WindowsLauncher() :
                new EC2UnixLauncher(), new EC2RetentionStrategy(idleTerminationMinutes), initScript, tmpDir, nodeProperties, remoteAdmin, jvmopts, false, idleTerminationMinutes, tags, cloudName, usePrivateDnsName, false, launchTimeout, amiType);

        this.name = name;
        this.spotInstanceRequestId = spotInstanceRequestId;
    }

    @Override
    protected boolean isAlive(boolean force) {
        return super.isAlive(force) || !this.isSpotRequestDead();
    }

    /**
     * Cancel the spot request for the instance. Terminate the instance if it is up. Remove the slave from Jenkins.
     */
    @Override
    public void terminate() {
		try {
			// Cancel the spot request
			AmazonEC2 ec2 = getCloud().connect();

			String instanceId = getInstanceId();
			List<String> requestIds = Collections.singletonList(spotInstanceRequestId);
			CancelSpotInstanceRequestsRequest cancelRequest = new CancelSpotInstanceRequestsRequest(requestIds);
			try {
				ec2.cancelSpotInstanceRequests(cancelRequest);
				LOGGER.info("Canceled Spot request: " + spotInstanceRequestId);

				// Terminate the slave if it is running
				if (instanceId != null && !instanceId.equals("")) {
					if (!super.isAlive(true)) {
						/*
						 * The node has been killed externally, so we've nothing to do here
						 */
						LOGGER.info("EC2 instance already terminated: " + instanceId);
					} else {
						TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(instanceId));
						ec2.terminateInstances(request);
						LOGGER.info("Terminated EC2 instance (terminated): " + instanceId);
					}

				}
			} catch (AmazonClientException e) {
				// Spot request is no longer valid
				LOGGER.log(Level.WARNING, "Failed to terminated instance and cancel Spot request: " + spotInstanceRequestId, e);
			}
		} catch (Exception e) {
			LOGGER.log(Level.WARNING,"Failed to remove slave: ", e);
		} finally {
			// Remove the instance even if deletion failed, otherwise it will hang around forever in
			// the nodes page. One way for this to occur is that an instance was terminated
			// manually or a spot instance was killed due to pricing. If we don't remove the node,
			// we screw up auto-scaling, since it will continue to count against the quota.
			try {
				Jenkins.getInstance().removeNode(this);
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Failed to remove slave: " + name, e);
			}
		}
    }

    /**
     * Retrieve the SpotRequest for a requestId
     *
     * @return SpotInstanceRequest object for this slave, or null if request is not valid anymore
     */
    @CheckForNull
    SpotInstanceRequest getSpotRequest() {
        AmazonEC2 ec2 = getCloud().connect();

        DescribeSpotInstanceRequestsRequest dsirRequest = new DescribeSpotInstanceRequestsRequest().withSpotInstanceRequestIds(this.spotInstanceRequestId);
        try {
            DescribeSpotInstanceRequestsResult dsirResult = ec2.describeSpotInstanceRequests(dsirRequest);
            List<SpotInstanceRequest> siRequests = dsirResult.getSpotInstanceRequests();

            return siRequests.get(0);
        } catch (AmazonClientException e) {
            // Spot request is no longer valid
            LOGGER.log(Level.WARNING, "Failed to fetch spot instance request for requestId: " + this.spotInstanceRequestId);
        }

        return null;
    }

    public boolean isSpotRequestDead() {
        SpotInstanceRequest spotRequest = getSpotRequest();
        if (spotRequest == null) {
            return true;
        }

        SpotInstanceState requestState = SpotInstanceState.fromValue(spotRequest.getState());
        return requestState == SpotInstanceState.Cancelled
                || requestState == SpotInstanceState.Closed
                || requestState == SpotInstanceState.Failed;
    }

    /**
     * Accessor for the spotInstanceRequestId
     */
    public String getSpotInstanceRequestId() {
        return spotInstanceRequestId;
    }

    @Override
    public String getInstanceId() {
        if (StringUtils.isEmpty(instanceId)) {
            SpotInstanceRequest sr = getSpotRequest();
            if (sr != null)
                instanceId = sr.getInstanceId();
        }
        return instanceId;
    }

    @Override
    public void onConnected() {
        // The spot request has been fulfilled and is connected. If the Spot
        // request had tags, we want those on the instance.
        pushLiveInstancedata();
    }

    @Extension
    public static final class DescriptorImpl extends EC2AbstractSlave.DescriptorImpl {

        @Override
        public String getDisplayName() {
            return Messages.EC2SpotSlave_AmazonEC2SpotInstance();
        }
    }

    @Override
    public String getEc2Type() {
        String spotMaxBidPrice = this.getSpotRequest().getSpotPrice();
        return Messages.EC2SpotSlave_Spot1() + spotMaxBidPrice.substring(0, spotMaxBidPrice.length() - 3)
                + Messages.EC2SpotSlave_Spot2();
    }

}
