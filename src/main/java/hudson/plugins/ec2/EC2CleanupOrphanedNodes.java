package hudson.plugins.ec2;

import com.google.common.annotations.VisibleForTesting;
import hudson.Extension;
import hudson.model.PeriodicWork;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    private final Logger LOGGER = Logger.getLogger(EC2CleanupOrphanedNodes.class.getName());
    @VisibleForTesting
    static final String NODE_IN_USE_LABEL_KEY = "jenkins_node_last_refresh";
    private static final long RECURRENCE_PERIOD = Long.parseLong(
            System.getProperty(EC2CleanupOrphanedNodes.class.getName() + ".recurrencePeriod", String.valueOf(HOUR)));
    private static final int LOST_MULTIPLIER = 3;

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD;
    }

    @Override
    protected void doRun() {
        LOGGER.fine("Starting clean up activity for orphaned nodes");
        getClouds().forEach(this::cleanCloud);
    }

    @VisibleForTesting
    void cleanCloud(EC2Cloud cloud) {
        if(!cloud.isCleanUpOrphanedNodes()){
            LOGGER.fine("Skipping clean up activity for cloud: " + cloud.getDisplayName() + " as it is disabled.");
            return;
        }
        LOGGER.fine("Processing clean up activity cloud: " + cloud.getDisplayName());
        Ec2Client connection;
        try {
            connection = cloud.connect();
        } catch (SdkException e) {
            LOGGER.log(Level.WARNING, "Failed to connect to EC2 cloud: " + cloud.getDisplayName(), e);
            return;
        }

        List<Instance> remoteInstances = getAllRunningInstances(connection, cloud);
        List<String> localConnectedEC2Instances = getConnectedAgentInstanceIds(cloud);
        addMissingTags(connection, remoteInstances, cloud);
        updateLocalInstancesTag(connection, localConnectedEC2Instances, remoteInstances, cloud);

        remoteInstances.stream()
                .filter(remote -> isOrphaned(remote))
                .forEach(remote -> terminateInstance(remote, connection));
    }

    private List<EC2Cloud> getClouds() {
        return Jenkins.get().clouds.getAll(EC2Cloud.class);
    }

    /**
     * Returns a list of all EC2 instances in states (running, pending, or stopping) AND with the tags
     * jenkins_server_url and jenkins_slave_type
     * These are all the instances that are created by the EC2 plugin
     */
    private List<Instance> getAllRunningInstances(Ec2Client connection, EC2Cloud cloud) throws SdkException {
        List<Instance> instances = new ArrayList<>();
        DescribeInstancesRequest dir = DescribeInstancesRequest.builder()
                .maxResults(100)
                .filters(
                        Filter.builder()
                                .name("instance-state-name")
                                .values("running", "pending", "stopping")
                                .build(),
                        Filter.builder()
                                .name("tag-key")
                                .values(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)
                                .build(),
                        Filter.builder()
                                .name("tag-key")
                                .values(EC2Tag.TAG_NAME_JENKINS_SERVER_URL)
                                .build())
                .build();
        DescribeInstancesResponse result = null;
        do {
            result = connection.describeInstances(dir);
            for (Reservation r : result.reservations()) {
                instances.addAll(r.instances());
            }
        } while (result.nextToken() != null);
        LOGGER.fine("Found " + instances.size() + " remote instance(s) for cloud: " + cloud.getDisplayName());
        return instances;
    }

    /**
     * Returns a list of EC2 agent instance IDs connected to Jenkins.
     */
    private List<String> getConnectedAgentInstanceIds(EC2Cloud cloud) {
        List<String> agentInstanceIds = new ArrayList<>();
        for (hudson.model.Node node : Jenkins.get().getNodes()) {
            if (node instanceof EC2AbstractSlave) {
                EC2AbstractSlave ec2Node = (EC2AbstractSlave) node;
                if (ec2Node.getCloud() == cloud) {
                    String instanceId = ec2Node.getInstanceId();
                    agentInstanceIds.add(instanceId);
                    LOGGER.fine("Connected agent: " + ec2Node.getNodeName() + ", EC2 Instance ID: " + instanceId
                            + ", Cloud: " + cloud.getDisplayName());
                }
            }
        }
        return agentInstanceIds;
    }

    /**
     * Adds a tag to each remote instance that does not have the jenkins_node_last_refresh tag.
     */
    private void addMissingTags(Ec2Client connection, List<Instance> remoteInstances, EC2Cloud cloud) {
        var tagKey = NODE_IN_USE_LABEL_KEY;
        var tagValue = OffsetDateTime.now(ZoneOffset.UTC).toString();
        List<String> instancesToTag = new ArrayList<>();

        for (Instance remoteInstance : remoteInstances) {
            boolean hasTag = remoteInstance.tags().stream()
                    .anyMatch(tag -> tagKey.equals(tag.key()));
            if (!hasTag) {
                instancesToTag.add(remoteInstance.instanceId());
            }
        }

        // Add tags for all collected instance IDs in bulk
        if (!instancesToTag.isEmpty()) {
            LOGGER.fine("Adding tag for instances " + instancesToTag + " with value " + tagValue + " in cloud: "
                    + cloud.getDisplayName());
            try {
                connection.createTags(builder -> builder.resources(instancesToTag)
                        .tags(software.amazon.awssdk.services.ec2.model.Tag.builder()
                                .key(tagKey)
                                .value(tagValue)
                                .build())
                        .build());
                LOGGER.fine("Added tag for instances " + instancesToTag + " with value " + tagValue + " in cloud: "
                        + cloud.getDisplayName());
            } catch (SdkException e) {
                LOGGER.log(Level.WARNING, "Error adding tags for instances " + instancesToTag, e);
            }
        }
    }

    /**
     * Updates the tag of the local EC2 instances to indicate they are still in use.
     */
    private void updateLocalInstancesTag(
            Ec2Client connection, List<String> localInstanceIds, List<Instance> remoteInstances, EC2Cloud cloud) {
        if (localInstanceIds.isEmpty()) {
            LOGGER.fine("No local EC2 agents found, skipping tag update.");
            return;
        }

        var remoteInstancesById =
                remoteInstances.stream().collect(Collectors.toMap(Instance::instanceId, instance -> instance));
        var tagKey = NODE_IN_USE_LABEL_KEY;
        var tagValue = OffsetDateTime.now(ZoneOffset.UTC).toString();
        List<String> instanceIdsToUpdate = new ArrayList<>();
        for (String instanceId : localInstanceIds) {
            var remoteInstance = remoteInstancesById.get(instanceId);
            if (remoteInstance != null) {
                instanceIdsToUpdate.add(instanceId);
            }
        }

        // Update tags for all collected instance IDs in bulk
        if (!instanceIdsToUpdate.isEmpty()) {
            try {
                connection.createTags(builder -> builder.resources(instanceIdsToUpdate)
                        .tags(software.amazon.awssdk.services.ec2.model.Tag.builder()
                                .key(tagKey)
                                .value(tagValue)
                                .build())
                        .build());
                LOGGER.fine("Updated tag for instances " + instanceIdsToUpdate + " to " + tagValue + " in cloud: "
                        + cloud.getDisplayName());
            } catch (SdkException e) {
                LOGGER.log(Level.WARNING, "Error updating tags for instances " + instanceIdsToUpdate, e);
            }
        }
    }

    private boolean isOrphaned(Instance remote) {
        String nodeLastRefresh = null;
        if (remote.tags() != null) {
            nodeLastRefresh = remote.tags().stream()
                    .filter(tag -> NODE_IN_USE_LABEL_KEY.equals(tag.key()))
                    .map(tag -> tag.value())
                    .findFirst()
                    .orElse(null);
        }
        if (nodeLastRefresh == null) {
            LOGGER.fine("Instance " + remote.instanceId() + " does not have the tag " + NODE_IN_USE_LABEL_KEY);
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
        LOGGER.fine("Instance " + remote.instanceId() + " jenkins_node_last_refresh tag value: " + nodeLastRefresh
                + ", isOrphan: " + isOrphan);
        return isOrphan;
    }

    private void terminateInstance(Instance remote, Ec2Client connection) {
        String instanceId = remote.instanceId();
        LOGGER.info("Removing orphaned instance: " + instanceId);
        try {
            connection.terminateInstances(
                    builder -> builder.instanceIds(instanceId).build());
        } catch (SdkException ex) {
            LOGGER.log(Level.WARNING, "Error terminating remote instance " + instanceId, ex);
        }
    }
}
