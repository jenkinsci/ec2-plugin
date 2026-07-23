/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
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
package hudson.plugins.ec2.cloudstats;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.slaves.ComputerListener;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.variant.OptionalExtension;

/**
 * Advances an EC2 agent's {@code cloud-stats} {@link ProvisioningActivity} through its launch phases and records a
 * launch failure when one occurs. It is an {@code @OptionalExtension(requirePlugins = "cloud-stats")} living outside
 * the always-loaded core, so it is class-loaded only when {@code cloud-stats} is installed; the core never references
 * it or any {@code cloud-stats} type.
 *
 * <p>cloud-stats ships its own {@code OperationListener}, but that resolves the activity only for computers extending
 * {@code AbstractCloudComputer}. EC2 agents are plain {@link hudson.slaves.SlaveComputer}s, so it silently no-ops for
 * them. This listener fills the gap: it resolves the activity from the agent node's opaque correlation id (see
 * {@link EC2CloudStatsProvisioningTracker#resolve(String)}) and drives {@code LAUNCHING} on preLaunch and
 * {@code OPERATING} on online, and on a launch failure attaches a {@code FAIL} so the activity never dangles. It is
 * scoped strictly to {@link EC2AbstractSlave} nodes, so it has no effect on agents contributed by other plugins.
 */
@OptionalExtension(requirePlugins = "cloud-stats")
public class EC2CloudStatsComputerListener extends ComputerListener {

    @Override
    public void preLaunch(Computer c, TaskListener listener) {
        ProvisioningActivity activity = activityFor(c);
        if (activity == null || !activity.enterIfNotAlready(ProvisioningActivity.Phase.LAUNCHING)) {
            return;
        }
        EC2CloudStatsProvisioningTracker.persist(ProvisioningActivity.Phase.LAUNCHING + " phase for " + c.getName());
    }

    @Override
    public void onOnline(Computer c, TaskListener listener) {
        Node node = c.getNode();
        ProvisioningActivity activity = activityFor(node);
        if (activity == null) {
            return;
        }
        activity.enterIfNotAlready(ProvisioningActivity.Phase.OPERATING);
        // Relabel the activity from its temporary name to the real node name. ProvisioningActivity.rename is
        // package-private, so go through the public ProvisioningListener.onComplete, which renames the activity to
        // node.getDisplayName() and persists (also flushing the OPERATING transition above). onComplete re-resolves
        // the activity by id internally and would NPE on a miss, so it is only safe because we resolved a live
        // activity just above.
        CloudStatistics.ProvisioningListener.get().onComplete(activity.getId(), node);
    }

    /**
     * Records a launch failure on the agent's activity. The instance was provisioned (so the activity is never reaped
     * as a dangling provision), but its agent never came online; without this the activity would sit in
     * {@code LAUNCHING} forever. Attaching a {@code FAIL} to the current phase records the failure and, via
     * cloud-stats' {@code attach}, completes and archives the activity.
     */
    @Override
    public void onLaunchFailure(Computer c, TaskListener listener) {
        ProvisioningActivity activity = activityFor(c);
        if (activity == null) {
            return;
        }
        String reason = c.getOfflineCauseReason();
        if (reason == null || reason.isBlank()) {
            reason = "Agent " + c.getName() + " failed to launch";
        }
        EC2CloudStatsProvisioningTracker.attachFailure(activity, reason);
    }

    /**
     * Resolves the tracked activity for an EC2 agent's computer, or {@code null} if the computer is not a tracked EC2
     * agent.
     */
    @CheckForNull
    private static ProvisioningActivity activityFor(Computer c) {
        return activityFor(c.getNode());
    }

    /**
     * Resolves the tracked activity for an EC2 agent node from its opaque correlation id. A non-EC2 node, or one with
     * no correlation id (untracked, or provisioned while cloud-stats was absent), resolves to {@code null} and is
     * skipped by every callback.
     */
    @CheckForNull
    private static ProvisioningActivity activityFor(Node node) {
        if (!(node instanceof EC2AbstractSlave)) {
            return null;
        }
        return EC2CloudStatsProvisioningTracker.resolve(((EC2AbstractSlave) node).getCloudStatsCorrelationId());
    }
}
