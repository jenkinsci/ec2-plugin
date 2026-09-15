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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Lists the instances each EC2 cloud is paying for that no agent is using, and offers to terminate
 * them.
 *
 * <p>Orphans are otherwise invisible from Jenkins: the nodes page shows agents, and an orphan is
 * precisely an instance with no agent, so the only way to notice one has been to open the AWS
 * console and compare by hand. They still occupy the instance caps, so a build queue that will not
 * move can be explained entirely by instances that no longer exist as far as the rest of Jenkins is
 * concerned.
 *
 * <p>The listing is built when the page is opened rather than on a timer, because it costs an EC2
 * call per cloud.
 */
@Extension
public class EC2OrphanedInstancesManagementLink extends ManagementLink {

    private static final Logger LOGGER = Logger.getLogger(EC2OrphanedInstancesManagementLink.class.getName());

    @Override
    public String getIconFileName() {
        return "symbol-cloud";
    }

    @Override
    public String getDisplayName() {
        return "EC2 orphaned instances";
    }

    @Override
    public String getUrlName() {
        return "ec2-orphaned-instances";
    }

    @Override
    public String getDescription() {
        return "Running EC2 instances that no Jenkins agent is using. They cost money and count "
                + "against the instance caps until they are adopted or terminated.";
    }

    @NonNull
    @Override
    public Permission getRequiredPermission() {
        return Jenkins.ADMINISTER;
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.TROUBLESHOOTING;
    }

    /**
     * @return one entry per EC2 cloud, including clouds with no orphans, so that a clean cloud is
     *     distinguishable from one that could not be reached.
     */
    @SuppressWarnings("unused") // index.jelly
    public List<CloudOrphans> getCloudOrphans() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        List<CloudOrphans> all = new ArrayList<>();
        for (EC2Cloud cloud : Jenkins.get().clouds.getAll(EC2Cloud.class)) {
            try {
                all.add(new CloudOrphans(cloud.getDisplayName(), EC2OrphanedInstanceInventory.forCloud(cloud), null));
            } catch (SdkException e) {
                LOGGER.log(Level.WARNING, "Failed to list orphaned instances for " + cloud.getDisplayName(), e);
                all.add(new CloudOrphans(cloud.getDisplayName(), List.of(), e.getMessage()));
            }
        }
        return all;
    }

    /**
     * Terminates one instance on an administrator's say-so.
     *
     * <p>Takes the instance id rather than a position in the list because the list is rebuilt on
     * every render, and acting on a stale row should fail to find its instance rather than
     * terminate whatever has taken its place.
     */
    @RequirePOST
    @SuppressWarnings("unused") // index.jelly
    public HttpResponse doTerminate(@QueryParameter String cloudName, @QueryParameter String instanceId) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        for (EC2Cloud cloud : Jenkins.get().clouds.getAll(EC2Cloud.class)) {
            if (!cloud.getDisplayName().equals(cloudName)) {
                continue;
            }
            boolean stillOrphaned = EC2OrphanedInstanceInventory.forCloud(cloud).stream()
                    .anyMatch(orphan -> orphan.getInstanceId().equals(instanceId));
            if (!stillOrphaned) {
                LOGGER.log(Level.INFO, () -> "Not terminating " + instanceId + ": it is no longer orphaned");
                return HttpResponses.forwardToPreviousPage();
            }
            LOGGER.log(Level.INFO, () -> "Terminating orphaned instance " + instanceId + " on administrator request");
            cloud.connect().terminateInstances(builder -> builder.instanceIds(instanceId));
            return HttpResponses.forwardToPreviousPage();
        }
        return HttpResponses.forwardToPreviousPage();
    }

    /** The orphans of one cloud, or the reason its orphans could not be listed. */
    public static class CloudOrphans {

        private final String cloudName;
        private final List<EC2OrphanedInstance> orphans;
        private final String error;

        CloudOrphans(String cloudName, List<EC2OrphanedInstance> orphans, String error) {
            this.cloudName = cloudName;
            this.orphans = orphans;
            this.error = error;
        }

        public String getCloudName() {
            return cloudName;
        }

        public List<EC2OrphanedInstance> getOrphans() {
            return orphans;
        }

        public String getError() {
            return error;
        }

        public boolean isFailed() {
            return error != null;
        }
    }
}
