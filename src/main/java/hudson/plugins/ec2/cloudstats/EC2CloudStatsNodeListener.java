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

import hudson.model.Node;
import hudson.plugins.ec2.EC2AbstractSlave;
import jenkins.model.NodeListener;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.variant.OptionalExtension;

/**
 * Completes an EC2 agent's {@code cloud-stats} {@link ProvisioningActivity} when the agent's node is removed from
 * Jenkins. It is an {@code @OptionalExtension(requirePlugins = "cloud-stats")} living outside the always-loaded core,
 * so it is class-loaded only when {@code cloud-stats} is installed.
 *
 * <p>cloud-stats ships its own {@code SlaveCompletionDetector} (also a {@link NodeListener}) that completes an
 * activity on node removal, but it keys off {@code TrackedItem}, which {@link EC2AbstractSlave} no longer implements.
 * For an EC2 node it now finds no id and no-ops, so without this listener a healthy EC2 activity would never reach
 * {@code COMPLETED}. This listener restores that transition, resolving the activity from the node's opaque correlation
 * id (see {@link EC2CloudStatsProvisioningTracker#resolve(String)}) and entering {@code COMPLETED}. It is scoped
 * strictly to {@link EC2AbstractSlave} nodes, so it has no effect on agents contributed by other plugins.
 */
@OptionalExtension(requirePlugins = "cloud-stats")
public class EC2CloudStatsNodeListener extends NodeListener {

    @Override
    protected void onDeleted(Node node) {
        if (!(node instanceof EC2AbstractSlave)) {
            return;
        }
        ProvisioningActivity activity =
                EC2CloudStatsProvisioningTracker.resolve(((EC2AbstractSlave) node).getCloudStatsCorrelationId());
        if (activity == null) {
            return;
        }
        // Only persist when this call actually advanced the activity; a node removed after its activity already
        // completed (e.g. a launch failure that failed-and-archived it) needs no further write. cloud-stats'
        // SlaveCompletionDetector also archives here, but archive() is package-private; leaving the activity in the
        // active set at COMPLETED is not observably problematic.
        if (activity.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED)) {
            EC2CloudStatsProvisioningTracker.persist("completion on removal for activity " + activity.getId());
        }
    }
}
