package hudson.plugins.ec2;

import com.google.common.annotations.VisibleForTesting;
import hudson.Extension;
import hudson.model.PeriodicWork;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

@Extension
public class EC2CleanupOrphanedNodes extends PeriodicWork {

    protected final Logger LOGGER = Logger.getLogger(EC2CleanupOrphanedNodes.class.getName());
    public static final String NODE_IN_USE_LABEL_KEY = "jenkins_node_last_refresh";
    public static final long RECURRENCE_PERIOD = Long.parseLong(System.getProperty(
            EC2CleanupOrphanedNodes.class.getName() + ".recurrencePeriod", String.valueOf(1000 * 60)));
    private static final int LOST_MULTIPLIER = 3;
    // public static final DateTimeFormatter LAST_REFRESH_FORMATTER =
    //         DateTimeFormatter.ofPattern("yyyy_MM_dd't'HH_mm_ss_SSS'z'");

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD;
    }

    private void cleanCloud(EC2Cloud cloud) {
        LOGGER.fine("Processing cloud: " + cloud.getDisplayName());
        Ec2Client connection = cloud.connect();

        List<Instance> remoteInstances = getAllRunningInstances(connection);
        List<String> localConnectedEC2Instances = getConnectedAgentInstanceIds(cloud);

        if (!(localConnectedEC2Instances.isEmpty() || remoteInstances.isEmpty())) {
            updateLocalInstancesTag(connection, localConnectedEC2Instances, remoteInstances);
        }

        remoteInstances.stream()
                .filter(remote -> isOrphaned(remote, localConnectedEC2Instances))
                .forEach(remote -> terminateInstance(remote, connection));
    }

    @Override
    protected void doRun() throws Exception {
        LOGGER.fine("Starting clean up activity for orphaned nodes");
        getClouds().forEach(this::cleanCloud);
    }

    private List<EC2Cloud> getClouds() {
        return Jenkins.get().clouds.getAll(EC2Cloud.class);
    }

    /**
     * Returns a list of all EC2 instances in states running, pending, or stopping in the AWS account for the configured region.
     */
    private List<Instance> getAllRunningInstances(Ec2Client connection) throws SdkException {
        List<Instance> instances = new ArrayList<>();
        DescribeInstancesRequest dir = DescribeInstancesRequest.builder()
                .filters(Filter.builder()
                        .name("instance-state-name")
                        .values("running", "pending", "stopping")
                        .build())
                .build();
        DescribeInstancesResponse result = null;
        do {
            result = connection.describeInstances(dir);
            for (Reservation r : result.reservations()) {
                instances.addAll(r.instances());
            }
        } while (result.nextToken() != null);
        return instances;
    }

    /**
     * Returns a list of EC2 agent instance IDs connected to Jenkins for the given EC2Cloud.
     */
    private List<String> getConnectedAgentInstanceIds(EC2Cloud cloud) {
        List<String> agentInstanceIds = new ArrayList<>();
        for (hudson.model.Node node : Jenkins.get().getNodes()) {
            if (node instanceof EC2AbstractSlave) {
                EC2AbstractSlave ec2Node = (EC2AbstractSlave) node;
                if (ec2Node.getCloud() == cloud) {
                    String instanceId = ec2Node.getInstanceId();
                    agentInstanceIds.add(instanceId);
                    LOGGER.fine("Connected agent: " + ec2Node.getNodeName() + ", EC2 Instance ID: " + instanceId);
                }
            }
        }
        return agentInstanceIds;
    }

    /**
     * Updates the tag of the local EC2 instances to indicate they are still in use.
     */
    private void updateLocalInstancesTag(
            Ec2Client ec2Client, List<String> localInstanceIds, List<Instance> remoteInstances) {
        var remoteInstancesById =
                remoteInstances.stream().collect(Collectors.toMap(Instance::instanceId, instance -> instance));
        var tagKey = NODE_IN_USE_LABEL_KEY;
        var tagValue = OffsetDateTime.now(ZoneOffset.UTC).toString();
        for (String instanceId : localInstanceIds) {
            var remoteInstance = remoteInstancesById.get(instanceId);
            if (remoteInstance == null) {
                continue;
            }
            try {
                ec2Client.createTags(builder -> builder.resources(instanceId)
                        .tags(software.amazon.awssdk.services.ec2.model.Tag.builder()
                                .key(tagKey)
                                .value(tagValue)
                                .build())
                        .build());
                LOGGER.log(Level.FINEST, "Updated tag for instance " + instanceId + " to " + tagValue);
            } catch (SdkException e) {
                LOGGER.log(Level.WARNING, "Error updating tag for instance " + instanceId, e);
            }
        }
    }

    private boolean isOrphaned(Instance remote, List<String> localInstances) {
        if (localInstances.contains(remote.instanceId())) {
            return false;
        }
        String nodeLastRefresh = null;
        if (remote.tags() != null) {
            nodeLastRefresh = remote.tags().stream()
                    .filter(tag -> NODE_IN_USE_LABEL_KEY.equals(tag.key()))
                    .map(tag -> tag.value())
                    .findFirst()
                    .orElse(null);
        }
        if (nodeLastRefresh == null) {
            return false;
        }
        OffsetDateTime lastRefresh;
        try {
            lastRefresh = OffsetDateTime.parse(nodeLastRefresh); // Use default ISO-8601 parser
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse last refresh timestamp for instance " + remote.instanceId(), e);
            return false;
        }
        boolean isOrphan = lastRefresh
                .plus(RECURRENCE_PERIOD * LOST_MULTIPLIER, java.time.temporal.ChronoUnit.MILLIS)
                .isBefore(OffsetDateTime.now(ZoneOffset.UTC));
        LOGGER.log(
                Level.FINEST,
                "Instance " + remote.instanceId() + " last_refresh tag value: " + nodeLastRefresh + ", isOrphan: "
                        + isOrphan);
        return isOrphan;
    }

    private void terminateInstance(Instance remote, Ec2Client connection) {
        String instanceId = remote.instanceId();
        LOGGER.log(Level.INFO, "Removing orphaned instance: " + instanceId);
        try {
            connection.terminateInstances(
                    builder -> builder.instanceIds(instanceId).build());
        } catch (SdkException ex) {
            LOGGER.log(Level.WARNING, "Error terminating remote instance " + instanceId, ex);
        }
    }
}
