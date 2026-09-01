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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;

/**
 * The {@code cloud-stats}-free seam through which the always-loaded EC2 provisioning core records provisioning
 * activities. It is the <em>only</em> thing core calls to track provisioning: its signatures use nothing but
 * {@link String} and JDK/core types, so no class on the always-loaded path ever names a {@code cloud-stats} type and
 * the plugin stays load-safe when {@code cloud-stats} is not installed -- by construction, not by runtime guarding.
 *
 * <p>The sole implementation lives behind {@code @OptionalExtension(requirePlugins = "cloud-stats")} and is present
 * only when the {@code cloud-stats} plugin is installed. It mints the {@code cloud-stats} activity identity, opens the
 * activity, and hands core back that activity's stable fingerprint as an opaque correlation {@code String}. Core
 * persists that string on the agent and never interprets it; later phase transitions resolve the activity by
 * scanning for the one whose fingerprint matches. When {@code cloud-stats} is absent the extension list is empty,
 * {@link #get()} returns the {@link NoOp} instance, every call is a silent no-op, and the correlation id is
 * {@code null} (persisted harmlessly; every downstream listener skips a {@code null} id).
 *
 * <p>The "started" operations return the correlation id the caller must persist. The intermediate
 * {@code LAUNCHING}/{@code OPERATING} and terminal {@code COMPLETED}-on-removal transitions for agent-backed paths are
 * driven passively by the optional listeners off the persisted correlation id and are deliberately <em>not</em>
 * methods here; the {@code ec2} step, which registers no Jenkins node, is the only path that drives completion through
 * this seam.
 */
public abstract class EC2ProvisioningTracker implements ExtensionPoint {

    /**
     * Opens a provisioning activity for a planned agent whose node name is not yet known -- the async
     * {@code NodeProvisioner} path and the {@code ec2} step, both of which mint the identity before the agent exists.
     *
     * @param cloudName the display name of the originating {@link EC2Cloud}
     * @param templateDescription the originating {@link SlaveTemplate}'s display name
     * @return the opaque correlation id to persist on the agent, or {@code null} if no tracker is active
     */
    @CheckForNull
    public abstract String onProvisioningStarted(@NonNull String cloudName, @NonNull String templateDescription);

    /**
     * Opens a provisioning activity for an agent whose node name is already known -- the {@code NodeProvisioner}-bypass
     * paths (min-instances / spare-capacity and the UI/CLI provision button), which have the node in hand up front.
     *
     * @param cloudName the display name of the originating {@link EC2Cloud}
     * @param templateDescription the originating {@link SlaveTemplate}'s display name
     * @param nodeName the agent's node name, used to label the activity from the start
     * @return the opaque correlation id to persist on the agent, or {@code null} if no tracker is active
     */
    @CheckForNull
    public abstract String onProvisioningStarted(
            @NonNull String cloudName, @NonNull String templateDescription, @NonNull String nodeName);

    /**
     * Reports a provisioning attempt that yielded no agent (async request unfulfilled, bypass failure, step failure),
     * failing the activity so it never dangles. A {@code null} correlation id -- what the started operations return
     * when no tracker is active -- is a no-op.
     *
     * @param correlationId the id returned by an earlier {@code onProvisioningStarted}, or {@code null}
     * @param reason a human-readable failure reason
     */
    public abstract void onProvisioningFailed(@CheckForNull String correlationId, @NonNull String reason);

    /**
     * Completes an activity that no Jenkins node will ever advance -- specifically the {@code ec2} step, which
     * provisions a running instance but registers no node, so it self-manages the {@code OPERATING -> COMPLETED} jump
     * (skipping {@code LAUNCHING}) here. A {@code null} correlation id is a no-op.
     *
     * @param correlationId the id returned by an earlier {@code onProvisioningStarted}, or {@code null}
     */
    public abstract void onProvisioningCompleted(@CheckForNull String correlationId);

    /**
     * Returns the active tracker: the {@code cloud-stats}-backed extension when {@code cloud-stats} is installed, or
     * the silent {@link NoOp} otherwise. Never {@code null}, so core can call it unconditionally.
     */
    @NonNull
    public static EC2ProvisioningTracker get() {
        // lookup (not lookupFirst): the list is legitimately empty when cloud-stats is absent, and lookupFirst
        // throws on empty. An empty list is the no-op case, not an error.
        ExtensionList<EC2ProvisioningTracker> all = ExtensionList.lookup(EC2ProvisioningTracker.class);
        return all.isEmpty() ? NoOp.INSTANCE : all.get(0);
    }

    /**
     * The fallback used when no {@code cloud-stats}-backed tracker is on the extension list: every start yields a
     * {@code null} correlation id and every report is dropped. Not an {@code @Extension}, so it is never discovered by
     * {@link ExtensionList} -- it exists only as {@link #get()}'s no-op return.
     */
    private static final class NoOp extends EC2ProvisioningTracker {

        private static final NoOp INSTANCE = new NoOp();

        @Override
        public String onProvisioningStarted(String cloudName, String templateDescription) {
            return null;
        }

        @Override
        public String onProvisioningStarted(String cloudName, String templateDescription, String nodeName) {
            return null;
        }

        @Override
        public void onProvisioningFailed(String correlationId, String reason) {
            // no-op: no cloud-stats present, nothing to record
        }

        @Override
        public void onProvisioningCompleted(String correlationId) {
            // no-op: no cloud-stats present, nothing to record
        }
    }
}
