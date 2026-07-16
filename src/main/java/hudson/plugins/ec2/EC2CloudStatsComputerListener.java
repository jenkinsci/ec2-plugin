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
package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

/**
 * Advances the {@code cloud-stats} {@link ProvisioningActivity} of an EC2 agent through its launch phases.
 *
 * <p>cloud-stats ships its own {@code OperationListener}, but that listener resolves the activity identity only
 * for computers that extend {@code AbstractCloudComputer}. EC2 agents are plain {@link hudson.slaves.SlaveComputer}s,
 * so it silently no-ops for them. This listener fills the gap: it resolves the activity through the agent node
 * (which implements {@link org.jenkinsci.plugins.cloudstats.TrackedItem}) and drives the {@code LAUNCHING} and
 * {@code OPERATING} transitions. It is scoped strictly to {@link EC2AbstractSlave} nodes, so it has no effect on
 * agents contributed by other plugins, and cloud-stats' own gated listener still fires and harmlessly no-ops.
 */
@Extension
public class EC2CloudStatsComputerListener extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(EC2CloudStatsComputerListener.class.getName());

    @Override
    public void preLaunch(Computer c, TaskListener listener) {
        advance(c, ProvisioningActivity.Phase.LAUNCHING);
    }

    @Override
    public void onOnline(Computer c, TaskListener listener) {
        advance(c, ProvisioningActivity.Phase.OPERATING);
    }

    /**
     * Enters {@code phase} on the tracked activity of an EC2 agent, if not already there, and persists the change.
     * Resolving through the node keeps this working for a plain {@code SlaveComputer}; an untracked agent (no
     * injected identity) resolves to a {@code null} activity and is skipped.
     */
    private static void advance(Computer c, ProvisioningActivity.Phase phase) {
        Node node = c.getNode();
        if (!(node instanceof EC2AbstractSlave)) {
            return;
        }
        CloudStatistics stats = CloudStatistics.get();
        ProvisioningActivity activity = stats.getActivityFor((EC2AbstractSlave) node);
        if (activity == null || !activity.enterIfNotAlready(phase)) {
            return;
        }
        // Mirror cloud-stats' own OperationListener, which persists after entering a phase; save() is the public
        // equivalent of its package-private persist() (both just save() and log an IOException).
        try {
            stats.save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to persist cloud-stats " + phase + " phase for " + node.getNodeName(), e);
        }
    }
}
