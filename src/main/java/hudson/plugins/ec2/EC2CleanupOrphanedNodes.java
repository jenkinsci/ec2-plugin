/*
 * The MIT License
 *
 * Copyright (c) 2025, CloudBees, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.model.PeriodicWork;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

@Extension
public class EC2CleanupOrphanedNodes extends PeriodicWork {

    private final Logger LOGGER = Logger.getLogger(EC2CleanupOrphanedNodes.class.getName());

    @VisibleForTesting
    static final String NODE_EXPIRES_AT_TAG_NAME = "jenkins_node_expires_at";

    private static final long RECURRENCE_PERIOD = Long.parseLong(
            System.getProperty(EC2CleanupOrphanedNodes.class.getName() + ".recurrencePeriod", String.valueOf(HOUR)));
    private static final int LOST_MULTIPLIER =
            Integer.parseInt(System.getProperty(EC2CleanupOrphanedNodes.class.getName() + ".lostMultiplier", "3"));

    /**
     * Shortest gap between cleanups asked for by a cap check, per cloud.
     *
     * <p>A cloud at its cap says so on every provisioning attempt, and a burst of queued builds
     * produces a great many of those in a few seconds. Without a gap each one would start its own
     * sweep, and a sweep is several EC2 calls, so the response to being out of capacity would be to
     * spend the API budget that provisioning needs to recover.
     */
    private static final long REQUESTED_CLEANUP_COOLDOWN = Long.parseLong(System.getProperty(
            EC2CleanupOrphanedNodes.class.getName() + ".requestedCleanupCooldown", String.valueOf(5 * MIN)));

    private static final Map<String, Long> LAST_REQUESTED_CLEANUP = new ConcurrentHashMap<>();

    private static final ExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EC2CleanupOrphanedNodes-requested");
        t.setDaemon(true);
        return t;
    });

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD;
    }

    /**
     * Sweeps a cloud that has just found itself with no capacity, rather than leaving it to wait
     * for the next scheduled pass.
     *
     * <p>Being at the cap is the moment orphans matter: until then they are only wasted money, but
     * now they are the reason work is not being scheduled. An hour is a long time to leave a queue
     * standing for instances that are never coming back.
     *
     * <p>Returns immediately. Callers ask for this while holding the instance counting lock, and a
     * sweep makes EC2 calls, which is the combination that stalls provisioning in the first place.
     */
    static void requestCleanup(@NonNull EC2Cloud cloud) {
        if (!cloud.isCleanUpOrphanedNodes()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = LAST_REQUESTED_CLEANUP.get(cloud.getDisplayName());
        if (previous != null && now - previous < REQUESTED_CLEANUP_COOLDOWN) {
            return;
        }
        LAST_REQUESTED_CLEANUP.put(cloud.getDisplayName(), now);
        CLEANUP_EXECUTOR.submit(() -> {
            try {
                new EC2CleanupOrphanedNodes().cleanCloud(cloud);
            } catch (RuntimeException e) {
                Logger.getLogger(EC2CleanupOrphanedNodes.class.getName())
                        .log(Level.WARNING, "Requested orphan cleanup failed for " + cloud.getDisplayName(), e);
            }
        });
    }

    @Override
    protected void doRun() {
        LOGGER.fine(() -> "Starting clean up activity for orphaned nodes");
        getClouds().forEach(this::cleanCloud);
    }

    @VisibleForTesting
    void cleanCloud(@NonNull EC2Cloud cloud) {
        if (!cloud.isCleanUpOrphanedNodes()) {
            LOGGER.fine(
                    () -> "Skipping clean up activity for cloud: " + cloud.getDisplayName() + " as it is disabled.");
            return;
        }
        LOGGER.fine(() -> "Processing clean up activity for cloud: " + cloud.getDisplayName());
        Ec2Client connection;
        try {
            connection = cloud.connect();
        } catch (SdkException e) {
            LOGGER.log(Level.WARNING, "Failed to connect to EC2 cloud: " + cloud.getDisplayName(), e);
            return;
        }

        Set<Instance> remoteInstances = getAllRemoteInstance(connection, cloud);
        Set<String> localConnectedEC2Instances = getConnectedAgentInstanceIds(cloud);
        addMissingTags(connection, remoteInstances, cloud);
        Set<String> remoteInstancesIds =
                remoteInstances.stream().map(Instance::instanceId).collect(Collectors.toSet());
        Set<String> updatedInstances =
                updateLocalInstancesTag(connection, localConnectedEC2Instances, remoteInstancesIds, cloud);

        boolean cloudIdle = isCloudIdle(cloud, localConnectedEC2Instances);
        if (cloudIdle) {
            LOGGER.fine(() -> "Cloud " + cloud.getDisplayName() + " holds no agents and has no launches on the way, "
                    + "so its instances past the attach grace period are orphans regardless of their expiry tag");
        }

        remoteInstances.stream()
                // exclude instances that just got updated
                .filter(remote -> !updatedInstances.contains(remote.instanceId()))
                .filter(remote ->
                        isOrphaned(remote) || (cloudIdle && EC2OrphanedInstanceInventory.isPastAttachGrace(remote)))
                .forEach(remote -> terminateInstance(remote.instanceId(), connection));
    }

    /**
     * @return whether the cloud has nothing running and nothing on the way. Every instance EC2
     *     still reports for such a cloud is an orphan by definition: no agent holds one and no
     *     launch is in a position to claim one, so waiting out the expiry tag only pays for
     *     hardware that is already known to be lost.
     *     <p>Answered only once Jenkins has finished starting. Agents are restored from disk during
     *     startup, so a cloud inspected before that looks idle when it is not, and acting on the
     *     answer would terminate the instances of the agents about to come back.
     */
    private boolean isCloudIdle(@NonNull EC2Cloud cloud, @NonNull Set<String> connectedInstanceIds) {
        return connectedInstanceIds.isEmpty()
                && Jenkins.get().getInitLevel() == InitMilestone.COMPLETED
                && !cloud.hasLaunchesInProgress();
    }

    private List<EC2Cloud> getClouds() {
        return Jenkins.get().clouds.getAll(EC2Cloud.class);
    }

    /**
     * Returns a list of all EC2 instances in states (running, pending, or stopping) AND with the tags
     * jenkins_server_url and jenkins_cloud_name
     * These are all the instances that are created by the EC2 plugin of this controller and this cloud.
     */
    private Set<Instance> getAllRemoteInstance(Ec2Client connection, EC2Cloud cloud) throws SdkException {
        if (JenkinsLocationConfiguration.get().getUrl() == null) {
            LOGGER.warning(
                    "Jenkins server URL is not set in JenkinsLocationConfiguration.Returning empty remote instance list for cloud: "
                            + cloud.getDisplayName());
            return Set.of();
        }

        Set<Instance> instances = EC2OrphanedInstanceInventory.remoteInstances(connection, cloud);
        LOGGER.fine(() -> "Found " + instances.size() + " remote instance ID(s) for cloud: " + cloud.getDisplayName()
                + ". Instance IDs: "
                + instances.stream().map(Instance::instanceId).collect(Collectors.joining(", ")));
        return instances;
    }

    /**
     * Returns a list of EC2 agent instance IDs connected to Jenkins.
     */
    private Set<String> getConnectedAgentInstanceIds(EC2Cloud cloud) {
        return EC2OrphanedInstanceInventory.attachedInstanceIds(cloud);
    }

    /**
     * Adds a tag to each remote instance that does not have the jenkins_node_last_refresh tag.
     */
    private void addMissingTags(Ec2Client connection, Set<Instance> remoteInstances, EC2Cloud cloud) {
        Set<String> instancesToTag = new HashSet<>();

        for (Instance remoteInstance : remoteInstances) {
            boolean hasTag = remoteInstance.tags().stream().anyMatch(tag -> NODE_EXPIRES_AT_TAG_NAME.equals(tag.key()));
            if (!hasTag) {
                instancesToTag.add(remoteInstance.instanceId());
            }
        }

        if (instancesToTag.isEmpty()) {
            LOGGER.fine(() -> "No instances to tag in cloud: " + cloud.getDisplayName());
            return;
        }

        LOGGER.fine(() -> "Creating tags for " + instancesToTag.size() + " instances");
        createOrUpdateExpiryTagInBulk(connection, cloud, instancesToTag);
    }

    /**
     * Updates the tag of the local EC2 instances to indicate they are still in use.
     */
    private Set<String> updateLocalInstancesTag(
            Ec2Client connection, Set<String> localInstanceIds, Set<String> remoteInstanceIds, EC2Cloud cloud) {
        if (localInstanceIds.isEmpty()) {
            LOGGER.fine(() -> "No local EC2 agents found, skipping tag update.");
            return Set.of();
        }

        Set<String> instanceIdsToUpdate = Sets.intersection(remoteInstanceIds, localInstanceIds);

        if (instanceIdsToUpdate.isEmpty()) {
            LOGGER.fine(() -> "No local EC2 agents found in remote instances, skipping tag update.");
            return Set.of();
        }

        LOGGER.fine(() -> "Updating tags for " + instanceIdsToUpdate.size() + " instances");
        createOrUpdateExpiryTagInBulk(connection, cloud, instanceIdsToUpdate);
        return instanceIdsToUpdate;
    }

    private void createOrUpdateExpiryTagInBulk(Ec2Client connection, EC2Cloud cloud, Set<String> instancesToTag) {

        String nodeExpiresAtTagValue = OffsetDateTime.now(ZoneOffset.UTC)
                .plus(RECURRENCE_PERIOD * LOST_MULTIPLIER, ChronoUnit.MILLIS)
                .toString();

        // Split instancesToTag into batches to avoid exceeding AWS limits
        List<List<String>> batches = Lists.partition(new ArrayList<>(instancesToTag), 500);
        LOGGER.fine(() ->
                "Creating or updating tags in batches of " + batches.size() + " for cloud: " + cloud.getDisplayName());
        for (List<String> batch : batches) {
            try {
                connection.createTags(builder -> builder.resources(batch)
                        .tags(Tag.builder()
                                .key(NODE_EXPIRES_AT_TAG_NAME)
                                .value(nodeExpiresAtTagValue)
                                .build())
                        .build());
                LOGGER.finer(() -> "Created or Updated tag for instances " + batch + " to " + nodeExpiresAtTagValue
                        + " in cloud: " + cloud.getDisplayName());
            } catch (SdkException e) {
                LOGGER.log(Level.WARNING, "Error updating tags for instances " + batch, e);
            }
        }
    }

    private boolean isOrphaned(Instance remote) {
        String nodeExpiresAt;
        if (remote.tags() != null) {
            nodeExpiresAt = remote.tags().stream()
                    .filter(tag -> NODE_EXPIRES_AT_TAG_NAME.equals(tag.key()))
                    .map(Tag::value)
                    .findFirst()
                    .orElse(null);
        } else {
            nodeExpiresAt = null;
        }

        if (nodeExpiresAt == null) {
            LOGGER.fine(() -> "Instance " + remote.instanceId() + " does not have the tag " + NODE_EXPIRES_AT_TAG_NAME);
            return false;
        }
        String currentTime = OffsetDateTime.now(ZoneOffset.UTC).toString();

        // We can do a string compare since the format will always be ISO 8601
        boolean isOrphan = nodeExpiresAt.compareTo(currentTime) < 0;
        LOGGER.fine(() -> "Instance " + remote.instanceId() + ", nodeExpiresAt: " + nodeExpiresAt + ", currentDate: "
                + currentTime + ", isOrphan: " + isOrphan);
        return isOrphan;
    }

    private void terminateInstance(String instanceId, Ec2Client connection) {
        LOGGER.info(() -> "Removing orphaned instance: " + instanceId);
        try {
            connection.terminateInstances(
                    builder -> builder.instanceIds(instanceId).build());
        } catch (SdkException ex) {
            LOGGER.log(Level.WARNING, "Error terminating remote instance " + instanceId, ex);
        }
    }
}
