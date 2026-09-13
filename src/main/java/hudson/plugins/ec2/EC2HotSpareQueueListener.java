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
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import jenkins.model.Jenkins;

/**
 * Asks for a hot spare pass as soon as a build is ready to run.
 *
 * <p>Without this, the first build for a label that has nothing warm has to wait for the periodic
 * sweep before any spare is even requested, and the label's own agents cannot fill the gap: an
 * executor being taken is the other trigger, and with no capacity there is no executor to take.
 * That is minutes of latency spent on exactly the case the spares exist to cover.
 *
 * <p>{@link Queue.BuildableItem} is in the queue's buildable list by the time this runs, so the
 * pass this schedules counts it. No provisioning decision is made here: this runs under the Queue
 * lock, where the only safe thing to do is hand the work to
 * {@link MinimumInstanceChecker#scheduleCheck()} and return.
 */
@Extension
public class EC2HotSpareQueueListener extends QueueListener {

    @Override
    public void onEnterBuildable(Queue.BuildableItem item) {
        if (anyCloudKeepsSparesByLabel()) {
            MinimumInstanceChecker.scheduleCheck();
        }
    }

    /**
     * @return whether any EC2 cloud has a label rule at all. Which rules the build actually matches
     *     is worked out by the pass itself, off this thread.
     */
    private static boolean anyCloudKeepsSparesByLabel() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return false;
        }
        return jenkins.clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .anyMatch(cloud -> !cloud.getHotSpareConfigsByLabel().isEmpty());
    }
}
