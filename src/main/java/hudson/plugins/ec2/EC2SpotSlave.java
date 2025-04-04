package hudson.plugins.ec2;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.plugins.ec2.win.EC2WindowsLauncher;
import hudson.slaves.NodeProperty;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CancelSpotInstanceRequestsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsResponse;
import software.amazon.awssdk.services.ec2.model.SpotInstanceRequest;
import software.amazon.awssdk.services.ec2.model.SpotInstanceState;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

public class EC2SpotSlave extends EC2AbstractSlave implements EC2Readiness {
    private static final Logger LOGGER = Logger.getLogger(EC2SpotSlave.class.getName());

    private final String spotInstanceRequestId;

    @Deprecated
    public EC2SpotSlave(
            String name,
            String spotInstanceRequestId,
            String templateDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String initScript,
            String tmpDir,
            String labelString,
            String remoteAdmin,
            String jvmopts,
            String idleTerminationMinutes,
            List<EC2Tag> tags,
            String cloudName,
            int launchTimeout,
            AMITypeData amiType)
            throws FormException, IOException {
        this(
                name,
                spotInstanceRequestId,
                templateDescription,
                remoteFS,
                numExecutors,
                mode,
                initScript,
                tmpDir,
                labelString,
                remoteAdmin,
                jvmopts,
                idleTerminationMinutes,
                tags,
                cloudName,
                false,
                launchTimeout,
                amiType);
    }

    @Deprecated
    public EC2SpotSlave(
            String name,
            String spotInstanceRequestId,
            String templateDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String initScript,
            String tmpDir,
            String labelString,
            String remoteAdmin,
            String jvmopts,
            String idleTerminationMinutes,
            List<EC2Tag> tags,
            String cloudName,
            boolean usePrivateDnsName,
            int launchTimeout,
            AMITypeData amiType)
            throws FormException, IOException {
        this(
                templateDescription + " (" + name + ")",
                spotInstanceRequestId,
                templateDescription,
                remoteFS,
                numExecutors,
                mode,
                initScript,
                tmpDir,
                labelString,
                Collections.emptyList(),
                remoteAdmin,
                DEFAULT_JAVA_PATH,
                jvmopts,
                idleTerminationMinutes,
                tags,
                cloudName,
                launchTimeout,
                amiType,
                ConnectionStrategy.backwardsCompatible(usePrivateDnsName, false, false),
                -1);
    }

    @DataBoundConstructor
    public EC2SpotSlave(
            String name,
            String spotInstanceRequestId,
            String templateDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String initScript,
            String tmpDir,
            String labelString,
            List<? extends NodeProperty<?>> nodeProperties,
            String remoteAdmin,
            String javaPath,
            String jvmopts,
            String idleTerminationMinutes,
            List<EC2Tag> tags,
            String cloudName,
            int launchTimeout,
            AMITypeData amiType,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses)
            throws FormException, IOException {

        super(
                name,
                "",
                templateDescription,
                remoteFS,
                numExecutors,
                mode,
                labelString,
                amiType.isWindows() ? new EC2WindowsLauncher() : new EC2UnixLauncher(),
                new EC2RetentionStrategy(idleTerminationMinutes),
                initScript,
                tmpDir,
                nodeProperties,
                remoteAdmin,
                javaPath,
                jvmopts,
                false,
                idleTerminationMinutes,
                tags,
                cloudName,
                launchTimeout,
                amiType,
                connectionStrategy,
                maxTotalUses,
                null,
                DEFAULT_METADATA_ENDPOINT_ENABLED,
                DEFAULT_METADATA_TOKENS_REQUIRED,
                DEFAULT_METADATA_HOPS_LIMIT,
                DEFAULT_METADATA_SUPPORTED,
                DEFAULT_ENCLAVE_ENABLED);

        this.name = name;
        this.spotInstanceRequestId = spotInstanceRequestId;
    }

    @Override
    protected boolean isAlive(boolean force) {
        return super.isAlive(force) || !this.isSpotRequestDead();
    }

    /**
     * Cancel the spot request for the instance. Terminate the instance if it is up. Remove the agent from Jenkins.
     */
    @Override
    public void terminate() {
        if (terminateScheduled.getCount() == 0) {
            synchronized (terminateScheduled) {
                if (terminateScheduled.getCount() == 0) {
                    Computer.threadPoolForRemoting.submit(() -> {
                        try {
                            // Cancel the spot request
                            Ec2Client ec2 = getCloud().connect();

                            String instanceId = getInstanceId();
                            List<String> requestIds = Collections.singletonList(spotInstanceRequestId);
                            CancelSpotInstanceRequestsRequest cancelRequest =
                                    CancelSpotInstanceRequestsRequest.builder()
                                            .spotInstanceRequestIds(requestIds)
                                            .build();
                            try {
                                ec2.cancelSpotInstanceRequests(cancelRequest);
                                LOGGER.info("Cancelled Spot request: " + spotInstanceRequestId);
                            } catch (SdkException e) {
                                // Spot request is no longer valid
                                LOGGER.log(Level.WARNING, "Failed to cancel Spot request: " + spotInstanceRequestId, e);
                            }

                            // Terminate the agent if it is running
                            if (instanceId != null && !instanceId.isEmpty()) {
                                if (!super.isAlive(true)) {
                                    /*
                                     * The node has been killed externally, so we've nothing to do here
                                     */
                                    LOGGER.info("EC2 instance already terminated: " + instanceId);
                                } else {
                                    TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                                            .instanceIds(Collections.singletonList(instanceId))
                                            .build();
                                    try {
                                        ec2.terminateInstances(request);
                                        LOGGER.info("Terminated EC2 instance (terminated): " + instanceId);
                                    } catch (SdkException e) {
                                        // Spot request is no longer valid
                                        LOGGER.log(
                                                Level.WARNING,
                                                "Failed to terminate the Spot instance: " + instanceId,
                                                e);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Failed to remove agent: ", e);
                        } finally {
                            // Remove the instance even if deletion failed, otherwise it will hang around forever in
                            // the nodes page. One way for this to occur is that an instance was terminated
                            // manually or a spot instance was killed due to pricing. If we don't remove the node,
                            // we screw up auto-scaling, since it will continue to count against the quota.
                            try {
                                Jenkins.get().removeNode(this);
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, "Failed to remove agent: " + name, e);
                            }
                            synchronized (terminateScheduled) {
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
     * @return SpotInstanceRequest object for this agent, or null if request is not valid anymore
     */
    @CheckForNull
    SpotInstanceRequest getSpotRequest() {
        Ec2Client ec2 = getCloud().connect();

        if (this.spotInstanceRequestId == null) {
            return null;
        }

        DescribeSpotInstanceRequestsRequest dsirRequest = DescribeSpotInstanceRequestsRequest.builder()
                .spotInstanceRequestIds(this.spotInstanceRequestId)
                .build();
        try {
            DescribeSpotInstanceRequestsResponse dsirResult = ec2.describeSpotInstanceRequests(dsirRequest);
            List<SpotInstanceRequest> siRequests = dsirResult.spotInstanceRequests();

            return siRequests.get(0);
        } catch (SdkException e) {
            // Spot request is no longer valid
            LOGGER.log(
                    Level.WARNING,
                    "Failed to fetch spot instance request for requestId: " + this.spotInstanceRequestId);
        }

        return null;
    }

    public boolean isSpotRequestDead() {
        SpotInstanceRequest spotRequest = getSpotRequest();
        if (spotRequest == null) {
            return true;
        }

        SpotInstanceState requestState =
                SpotInstanceState.fromValue(spotRequest.state().toString());
        return requestState == SpotInstanceState.CANCELLED
                || requestState == SpotInstanceState.CLOSED
                || requestState == SpotInstanceState.FAILED;
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
            if (sr != null) {
                instanceId = sr.instanceId();
            }
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
        SpotInstanceRequest spotRequest = getSpotRequest();
        if (spotRequest != null) {
            String spotMaxBidPrice = spotRequest.spotPrice();
            return Messages.EC2SpotSlave_Spot1()
                    + spotMaxBidPrice.substring(0, spotMaxBidPrice.length() - 3)
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
            return sr.status().message();
        }
        throw SdkException.builder().message("No spot instance request").build();
    }
}
