package hudson.plugins.ec2;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.plugins.ec2.win.EC2WindowsLauncher;
import hudson.slaves.NodeProperty;

import javax.annotation.CheckForNull;

public class EC2SpotSlave extends EC2AbstractSlave implements EC2Readiness {
    private static final Logger LOGGER = Logger.getLogger(EC2SpotSlave.class.getName());

    private final String spotInstanceRequestId;
    private boolean restartSpotInterruption;

    @Deprecated
    public EC2SpotSlave(String name, String spotInstanceRequestId, String templateDescription, String remoteFS, int numExecutors, Mode mode, String initScript, String tmpDir, String labelString, String remoteAdmin, String jvmopts, String idleTerminationMinutes, List<EC2Tag> tags, String cloudName, int launchTimeout, AMITypeData amiType)
            throws FormException, IOException {
        this(name, spotInstanceRequestId, templateDescription, remoteFS, numExecutors, mode, initScript, tmpDir, labelString, remoteAdmin, jvmopts, idleTerminationMinutes, tags, cloudName, false, launchTimeout, amiType);
    }

    @Deprecated
    public EC2SpotSlave(String name, String spotInstanceRequestId, String templateDescription, String remoteFS, int numExecutors, Mode mode, String initScript, String tmpDir, String labelString, String remoteAdmin, String jvmopts, String idleTerminationMinutes, List<EC2Tag> tags, String cloudName, boolean usePrivateDnsName, int launchTimeout, AMITypeData amiType)
            throws FormException, IOException {
        this(templateDescription + " (" + name + ")", spotInstanceRequestId, templateDescription, remoteFS, numExecutors, mode, initScript, tmpDir, labelString, Collections.emptyList(), remoteAdmin, jvmopts, idleTerminationMinutes, tags, cloudName, launchTimeout, amiType, ConnectionStrategy.backwardsCompatible(usePrivateDnsName, false, false), -1, false);
    }

    @Deprecated
    public EC2SpotSlave(String name, String spotInstanceRequestId, String templateDescription, String remoteFS, int numExecutors, Mode mode, String initScript, String tmpDir, String labelString, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String jvmopts, String idleTerminationMinutes, List<EC2Tag> tags, String cloudName, int launchTimeout, AMITypeData amiType, ConnectionStrategy connectionStrategy, int maxTotalUses)
            throws FormException, IOException {
        this(name, spotInstanceRequestId, templateDescription, remoteFS, numExecutors, mode, initScript, tmpDir, labelString, nodeProperties, remoteAdmin, jvmopts, idleTerminationMinutes, tags, cloudName, launchTimeout, amiType, connectionStrategy, maxTotalUses, false);
    }

    @DataBoundConstructor
    public EC2SpotSlave(String name, String spotInstanceRequestId, String templateDescription, String remoteFS, int numExecutors, Mode mode, String initScript, String tmpDir, String labelString, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String jvmopts, String idleTerminationMinutes, List<EC2Tag> tags, String cloudName, int launchTimeout, AMITypeData amiType, ConnectionStrategy connectionStrategy, int maxTotalUses, boolean restartSpotInterruption)
            throws FormException, IOException {

        super(name, "", templateDescription, remoteFS, numExecutors, mode, labelString, amiType.isWindows() ? new EC2WindowsLauncher() :
                new EC2UnixLauncher(), new EC2RetentionStrategy(idleTerminationMinutes), initScript, tmpDir, nodeProperties, remoteAdmin, jvmopts, false, idleTerminationMinutes, tags, cloudName, false, launchTimeout, amiType, connectionStrategy, maxTotalUses);

        this.name = name;
        this.spotInstanceRequestId = spotInstanceRequestId;
        this.restartSpotInterruption = restartSpotInterruption;
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
        if (terminateScheduled.getCount() == 0) {
            synchronized(terminateScheduled) {
                if (terminateScheduled.getCount() == 0) {
                    Computer.threadPoolForRemoting.submit(() -> {
                        try {
                            // Cancel the spot request
                            AmazonEC2 ec2 = getCloud().connect();

                            String instanceId = getInstanceId();
                            List<String> requestIds = Collections.singletonList(spotInstanceRequestId);
                            CancelSpotInstanceRequestsRequest cancelRequest = new CancelSpotInstanceRequestsRequest(requestIds);
                            try {
                                ec2.cancelSpotInstanceRequests(cancelRequest);
                                LOGGER.info("Cancelled Spot request: " + spotInstanceRequestId);
                            } catch (AmazonClientException e) {
                                // Spot request is no longer valid
                                LOGGER.log(Level.WARNING, "Failed to cancel Spot request: " + spotInstanceRequestId, e);
                            }

                            // Terminate the slave if it is running
                            if (instanceId != null && !instanceId.equals("")) {
                                if (!super.isAlive(true)) {
                                    /*
                                    * The node has been killed externally, so we've nothing to do here
                                    */
                                    LOGGER.info("EC2 instance already terminated: " + instanceId);
                                } else {
                                    TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(instanceId));
                                    try {
                                        ec2.terminateInstances(request);
                                        LOGGER.info("Terminated EC2 instance (terminated): " + instanceId);
                                    } catch (AmazonClientException e) {
                                        // Spot request is no longer valid
                                        LOGGER.log(Level.WARNING, "Failed to terminate the Spot instance: " + instanceId, e);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING,"Failed to remove slave: ", e);
                        } finally {
                            // Remove the instance even if deletion failed, otherwise it will hang around forever in
                            // the nodes page. One way for this to occur is that an instance was terminated
                            // manually or a spot instance was killed due to pricing. If we don't remove the node,
                            // we screw up auto-scaling, since it will continue to count against the quota.
                            try {
                                Jenkins.get().removeNode(this);
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, "Failed to remove slave: " + name, e);
                            }
                            synchronized(terminateScheduled) {
                                terminateScheduled.countDown();
                            }
                        }
                    });
                    terminateScheduled.reset();
                }
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

        if (this.spotInstanceRequestId == null) {
            return null;
        }

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

    /**
     * Gets whether the node has the setting configured to restart all its tasks when a spot interruption event occurs
     * @return true if the node's tasks should be restarted
     */
    public boolean getRestartSpotInterruption() {
        return restartSpotInterruption;
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
        SpotInstanceRequest spotRequest = getSpotRequest();
        if (spotRequest != null) {
            String spotMaxBidPrice = spotRequest.getSpotPrice();
            return Messages.EC2SpotSlave_Spot1() + spotMaxBidPrice.substring(0, spotMaxBidPrice.length() - 3)
                    + Messages.EC2SpotSlave_Spot2();
        }
        return null;
    }

    @Override
    public boolean isReady() {
        return getInstanceId() != null;
    }

    @Override
    public String getEc2ReadinessStatus() {
        SpotInstanceRequest sr = getSpotRequest();
        if (sr != null) {
            return sr.getStatus().getMessage();
        }
        throw new AmazonClientException("No spot instance request");
    }
}
