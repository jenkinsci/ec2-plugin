/*
 * The MIT License
 *
 * Copyright (c) 2026, CloudBees, Inc., and a number of other of contributors
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * The instances EC2 reports for a cloud that no agent is using.
 *
 * <p>An instance becomes an orphan whenever the record of it on the Jenkins side is lost while the
 * instance itself keeps running: a launch that never finished attaching, an agent deleted without
 * its instance being terminated, a controller that lost its node. Nothing on the Jenkins side
 * points at it any more, so only EC2 knows it exists, and it goes on costing money and occupying
 * the instance caps until something looks for it.
 *
 * <p>Shared by the two things that care. {@link EC2CleanupOrphanedNodes} terminates what it finds
 * here, and {@link EC2OrphanedInstancesManagementLink} shows it to an administrator, so both give
 * the same answer about the same instances.
 */
public final class EC2OrphanedInstanceInventory {

    /**
     * How long an instance is given to become an agent before its lack of one is taken as failure.
     *
     * <p>An instance is not an agent for the first part of its life no matter how well the launch
     * is going, and the in-flight record the launch leaves behind expires after a couple of
     * minutes, which is shorter than a slow boot and connect. Age is therefore what separates an
     * instance that failed to attach from one that has not finished trying, and anything younger
     * than this is given the benefit of the doubt.
     */
    static final long ATTACH_GRACE_MILLIS =
            Long.getLong("jenkins.ec2.attachGracePeriodMs", TimeUnit.MINUTES.toMillis(10));

    private EC2OrphanedInstanceInventory() {}

    /**
     * @return whether an instance has been up long enough that the absence of an agent means it
     *     failed to attach rather than that it is still booting.
     */
    static boolean isPastAttachGrace(@NonNull Instance instance) {
        Instant launched = instance.launchTime();
        return launched != null && launched.isBefore(Instant.now().minusMillis(ATTACH_GRACE_MILLIS));
    }

    /**
     * @return whether an instance is holding a place in the instance caps that it can never use.
     *     No agent holds it, it is past the age where it might still be attaching, and the template
     *     that launched it either no longer exists or refuses to adopt orphans, so no future launch
     *     will take it over either. Counting such an instance against a cap reserves capacity for
     *     work that can never be scheduled on it.
     *     <p>An orphan its template would adopt is not unusable and keeps its place, because the
     *     next launch from that template can turn it back into an agent.
     */
    static boolean isUnusableOrphan(
            @NonNull EC2Cloud cloud, @NonNull Instance instance, @NonNull Set<String> attachedInstanceIds) {
        if (attachedInstanceIds.contains(instance.instanceId()) || !isPastAttachGrace(instance)) {
            return false;
        }
        SlaveTemplate template = templateOf(cloud, instance);
        if (template == null) {
            return true;
        }
        /*
         * An instance that has never carried an agent is adoptable whatever the template says,
         * because the setting refuses instances that have been used rather than instances that
         * exist. Its place in the caps is therefore a place a future launch can still take up.
         */
        return template.isAvoidUsingOrphanedNodes() && !SlaveTemplate.hasNeverCarriedAnAgent(instance);
    }

    /**
     * @return every instance EC2 reports for this cloud, whether or not an agent holds it. Only the
     *     states a running instance passes through are asked for, so an instance already on its way
     *     down is not reported as something to act on.
     */
    static Set<Instance> remoteInstances(@NonNull Ec2Client connection, @NonNull EC2Cloud cloud) {
        String jenkinsUrl = JenkinsLocationConfiguration.get().getUrl();
        if (jenkinsUrl == null) {
            return Collections.emptySet();
        }

        Set<Instance> instances = new HashSet<>();
        String nextToken = null;
        do {
            DescribeInstancesResponse result = connection.describeInstances(DescribeInstancesRequest.builder()
                    .maxResults(500)
                    .filters(
                            Filter.builder()
                                    .name("instance-state-name")
                                    .values(
                                            InstanceState.RUNNING.getCode(),
                                            InstanceState.PENDING.getCode(),
                                            InstanceState.STOPPING.getCode())
                                    .build(),
                            tagFilter(EC2Tag.TAG_NAME_JENKINS_SERVER_URL, jenkinsUrl),
                            tagFilter(EC2Tag.TAG_NAME_JENKINS_CLOUD_NAME, cloud.getDisplayName()))
                    .nextToken(nextToken)
                    .build());

            for (Reservation reservation : result.reservations()) {
                instances.addAll(reservation.instances());
            }
            nextToken = result.nextToken();
        } while (nextToken != null);

        return instances;
    }

    /**
     * @return the instance ids of the agents this cloud currently holds, which is the set an
     *     instance has to be absent from to be an orphan.
     */
    static Set<String> attachedInstanceIds(@NonNull EC2Cloud cloud) {
        return Jenkins.get().getNodes().stream()
                .filter(EC2AbstractSlave.class::isInstance)
                .map(EC2AbstractSlave.class::cast)
                .filter(node -> cloud.equals(node.getCloud()))
                .map(EC2AbstractSlave::getInstanceId)
                .collect(Collectors.toSet());
    }

    static Filter tagFilter(String tagName, String tagValue) {
        return Filter.builder().name("tag:" + tagName).values(tagValue).build();
    }

    /**
     * Looks up what is orphaned in a cloud right now. Makes EC2 calls, so it belongs behind a
     * deliberate action rather than anything that renders on a schedule.
     *
     * @return the orphans, ordered oldest first, since the longest-running one has cost the most
     *     and is the least likely to still be on its way up.
     */
    public static List<EC2OrphanedInstance> forCloud(@NonNull EC2Cloud cloud) {
        Set<Instance> remote = remoteInstances(cloud.connect(), cloud);
        Set<String> attached = attachedInstanceIds(cloud);

        List<EC2OrphanedInstance> orphans = new ArrayList<>();
        for (Instance instance : remote) {
            if (attached.contains(instance.instanceId())) {
                continue;
            }
            orphans.add(new EC2OrphanedInstance(instance, templateOf(cloud, instance)));
        }
        orphans.sort((a, b) -> Long.compare(b.getAgeMillis(), a.getAgeMillis()));
        return orphans;
    }

    /**
     * @return the template that launched an instance, identified by the slave type tag the launch
     *     wrote, or {@code null} if no current template claims it. A null answer is normal rather
     *     than a fault: a template that has since been removed or renamed leaves instances behind
     *     that nothing will ever adopt, which is worth seeing in its own right.
     */
    @CheckForNull
    static SlaveTemplate templateOf(@NonNull EC2Cloud cloud, @NonNull Instance instance) {
        String slaveType = tagValue(instance, EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE);
        if (slaveType == null) {
            return null;
        }
        for (SlaveTemplate template : cloud.getTemplates()) {
            String description = template.getDescription();
            if (slaveType.equals(EC2Cloud.getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_DEMAND, description))
                    || slaveType.equals(EC2Cloud.getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_SPOT, description))) {
                return template;
            }
        }
        return null;
    }

    @CheckForNull
    static String tagValue(@NonNull Instance instance, @NonNull String key) {
        if (instance.tags() == null) {
            return null;
        }
        return instance.tags().stream()
                .filter(tag -> key.equals(tag.key()))
                .map(Tag::value)
                .findFirst()
                .orElse(null);
    }
}
