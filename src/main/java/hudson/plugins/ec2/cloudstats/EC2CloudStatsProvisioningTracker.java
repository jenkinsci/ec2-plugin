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
import hudson.plugins.ec2.EC2ProvisioningTracker;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.variant.OptionalExtension;

/**
 * The sole {@link EC2ProvisioningTracker} implementation: the bridge from the {@code cloud-stats}-free EC2 provisioning
 * core to the {@code cloud-stats} plugin. It is an {@code @OptionalExtension(requirePlugins = "cloud-stats")}, so it is
 * class-loaded and registered on the extension list <em>only</em> when {@code cloud-stats} is installed. When it is
 * absent this class is never loaded, {@link EC2ProvisioningTracker#get()} returns its no-op fallback, and no
 * {@code cloud-stats} type is ever touched.
 *
 * <p>It mints the {@code cloud-stats} activity identity, opens the activity, and returns the activity's stable
 * {@code fingerprint} to core as an opaque correlation {@link String}. Later phase transitions resolve that string back
 * to the activity via {@link #resolve(String)}, a fingerprint scan over {@link CloudStatistics#getActivities()} (active
 * and archived) -- deliberately <em>not</em> {@link CloudStatistics#getActivityFor(ProvisioningActivity.Id)}, which logs
 * a warning on a miss. Resolving by fingerprint over the persisted correlation id is what lets tracking survive a
 * controller restart: the agent's persisted id still matches its activity's fingerprint after reload.
 *
 * <p>The static {@link #correlationIdOf} and {@link #resolve} helpers are shared with the other optional
 * {@code cloud-stats} listeners ({@link EC2CloudStatsComputerListener}, {@link EC2CloudStatsNodeListener}); keeping them
 * here concentrates every fingerprint&harr;correlation-id conversion in one place.
 */
@OptionalExtension(requirePlugins = "cloud-stats")
public class EC2CloudStatsProvisioningTracker extends EC2ProvisioningTracker {

    private static final Logger LOGGER = Logger.getLogger(EC2CloudStatsProvisioningTracker.class.getName());

    @Override
    public String onProvisioningStarted(String cloudName, String templateDescription) {
        return open(new ProvisioningActivity.Id(cloudName, templateDescription));
    }

    @Override
    public String onProvisioningStarted(String cloudName, String templateDescription, String nodeName) {
        return open(new ProvisioningActivity.Id(cloudName, templateDescription, nodeName));
    }

    /**
     * Opens a new activity for {@code id} through cloud-stats' own {@code ProvisioningListener}, which registers and
     * persists it, then returns the opaque correlation id core must persist on the agent.
     */
    private static String open(ProvisioningActivity.Id id) {
        CloudStatistics.ProvisioningListener.get().onStarted(id);
        return correlationIdOf(id);
    }

    @Override
    public void onProvisioningFailed(@CheckForNull String correlationId, String reason) {
        ProvisioningActivity activity = resolve(correlationId);
        if (activity == null) {
            return;
        }
        attachFailure(activity, reason);
    }

    @Override
    public void onProvisioningCompleted(@CheckForNull String correlationId) {
        ProvisioningActivity activity = resolve(correlationId);
        if (activity == null) {
            return;
        }
        // The caller (the ec2 step) provisioned a genuinely running instance but registers no Jenkins node, so it
        // self-manages completion here. Advancing through OPERATING before COMPLETED avoids cloud-stats' "completed
        // before reaching OPERATING" warning on a healthy PROVISIONING -> COMPLETED jump; LAUNCHING is skipped because
        // no agent ever launches or connects. enterIfNotAlready (not enter) keeps this idempotent.
        activity.enterIfNotAlready(ProvisioningActivity.Phase.OPERATING);
        activity.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED);
        persist("completion for activity " + activity.getId());
    }

    /**
     * Renders an activity id's stable fingerprint as the opaque correlation {@link String} core persists. The
     * fingerprint is stable across a controller restart, so the persisted string still identifies the activity after a
     * reload.
     */
    static String correlationIdOf(ProvisioningActivity.Id id) {
        return Integer.toString(id.getFingerprint());
    }

    /**
     * Resolves a correlation id back to its {@link ProvisioningActivity} by scanning every activity cloud-stats knows
     * about (active and archived) for the one whose fingerprint matches. Returns {@code null} for a {@code null}
     * correlation id (cloud-stats was absent when the agent was provisioned) or when no activity matches. Scans rather
     * than calling {@link CloudStatistics#getActivityFor(ProvisioningActivity.Id)} precisely because that logs a
     * warning on a miss, which would be noise for the many legitimately untracked callbacks.
     */
    @CheckForNull
    static ProvisioningActivity resolve(@CheckForNull String correlationId) {
        if (correlationId == null) {
            return null;
        }
        for (ProvisioningActivity activity : CloudStatistics.get().getActivities()) {
            if (correlationId.equals(correlationIdOf(activity.getId()))) {
                return activity;
            }
        }
        return null;
    }

    /**
     * Finishes a failed activity by attaching a {@code FAIL} to its current phase. cloud-stats' {@code attach} enters
     * COMPLETED, archives, and persists in one step, so it uniformly finishes a failed activity whatever phase it was in
     * (PROVISIONING for an unfulfilled request or a step failure, LAUNCHING for a launch failure); no separate
     * {@link #persist} is needed. Shared with {@link EC2CloudStatsComputerListener#onLaunchFailure}.
     */
    static void attachFailure(ProvisioningActivity activity, String reason) {
        CloudStatistics.get()
                .attach(
                        activity,
                        activity.getCurrentPhase(),
                        new PhaseExecutionAttachment(ProvisioningActivity.Status.FAIL, reason));
    }

    /**
     * Persists cloud-stats after a phase transition, matching cloud-stats' own listeners. save() is the public
     * equivalent of its package-private persist(); a persistence failure is only logged -- cloud-stats observes
     * provisioning, it never breaks it.
     */
    static void persist(String what) {
        try {
            CloudStatistics.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "Unable to persist cloud-stats " + what);
        }
    }
}
