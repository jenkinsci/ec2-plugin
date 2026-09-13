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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * How many idle spares a label should be holding right now, learned from how well the spares have
 * been keeping up.
 *
 * <p>A target belongs to the label work asked for rather than to the rule that set the policy. Two
 * labels covered by one rule scale apart: a burst of x86 builds cannot warm arm64 hardware that
 * could never run them, even though one rule governs both.
 *
 * <p>A fixed number of spares is either wasted while nothing is building or exhausted the moment a
 * burst arrives, so the number is treated as a load prediction that follows demand:
 *
 * <ul>
 *   <li>an executor being taken is the signal that drives the whole thing. It says the label is in
 *       use and that the pool just lost a spare, so the replacement is provisioned there and then
 *       rather than at the next periodic pass. A label being used holds at least one step of
 *       spares, which is how a label warms up from nothing without waiting for builds to pile up;
 *   <li>every spare taken by a build is replaced, because a busy agent is not a spare. This is what
 *       keeps a label warm for the whole of a busy period rather than for the first few builds;
 *   <li>past that first step, the target is the work the label has in sight: the builds queued for
 *       it plus the agents running one. A single build fanning out to 20 parallel branches asks for
 *       20 spares at once rather than climbing there a step per minute, because the demand is
 *       already measurable;
 *   <li>when the pool runs dry anyway, or builds are waiting that neither the spares nor the
 *       instances already on their way can take, the target is raised a further
 *       {@link HotSpareConfigByLabel#getScalingFactor()} above the load, once per interval, until
 *       the spares do keep up;
 *   <li>the load plus one step is also the limit, so a label whose instance caps are already
 *       reached does not accumulate a backlog it would try to launch all at once later;
 *   <li>the target comes down a step per idle timeout, either towards the work still in sight while
 *       the label is in use, or to {@link HotSpareConfigByLabel#getBaseHotSpares()} - zero by
 *       default - once nothing is asking for the label at all, rather than paying for spares
 *       nothing wants.
 * </ul>
 *
 * <p>The target, not the idle timeout, is what decides how many spares a label keeps: an agent is
 * only reclaimed once the target has come down past it. Letting the timeout reclaim a spare the
 * target still wanted would pay for the same capacity twice, because the very next pass would
 * provision a replacement for it.
 *
 * <p>The target is deliberately not persisted: after a restart the label starts from its base count
 * and learns again within a few minutes, which is safer than restoring a number that described a
 * load that has since gone away.
 */
@Restricted(NoExternalUse.class)
public final class HotSpareDemand {

    private static final Logger LOGGER = Logger.getLogger(HotSpareDemand.class.getName());

    /**
     * How long a growth step has to be given before another one is allowed. Provisioning is not
     * instant and a check can be triggered by events rather than by the clock, so without this a
     * single burst would step the target up several times before the first instances even boot.
     */
    private static final long GROWTH_INTERVAL_MS = Long.getLong("jenkins.ec2.hotSpareGrowthIntervalMs", 60_000);

    private static final Map<String, HotSpareDemand> DEMANDS = new ConcurrentHashMap<>();

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Needs to be overridden from tests")
    public static Clock clock = Clock.systemDefaultZone();

    private int target;

    private long lastGrowthMillis;

    /** When the label was first seen with nothing to do, or 0 while it has work. */
    private long quietSinceMillis;

    /** When the load first came in under the target, or 0 while the target is not above it. */
    private long overshotSinceMillis;

    /** Spares taken by builds since the last pass, counted by {@link #spareConsumed}. */
    private int consumedSinceLastPass;

    /** The label this target belongs to, for the logs. */
    private final String label;

    private HotSpareDemand(String label) {
        this.label = label;
    }

    public static HotSpareDemand of(@NonNull EC2Cloud cloud, @NonNull String label) {
        return DEMANDS.computeIfAbsent(key(cloud, label), key -> new HotSpareDemand(label));
    }

    /**
     * @return the labels of a cloud that hold a target, so a label whose work has gone away is
     *     still visited by the next pass and fades a step at a time instead of dropping to nothing
     *     the moment its queue empties.
     */
    public static Set<String> trackedLabels(@NonNull EC2Cloud cloud) {
        String prefix = cloud.name + '\u0000';
        return DEMANDS.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Drops the targets of a cloud that have faded to nothing, so a controller does not hold an
     * entry for every label a job has ever asked for. A label that comes back is simply learned
     * again from the work that asks for it.
     *
     * <p>A target above zero is kept: spares are being held for it, and it has to fade a step at a
     * time rather than vanish. So is one with a spare taken since the last pass, which is a label
     * about to want something even though its target has not caught up yet.
     *
     * @param keep labels to hold on to whatever their target, for the labels the rules name: they
     *     are a fixed set, and their targets are where a rule's floor lives.
     */
    public static void forgetZeroed(@NonNull EC2Cloud cloud, @NonNull Set<String> keep) {
        String prefix = cloud.name + '\u0000';
        DEMANDS.entrySet().removeIf(entry -> {
            String key = entry.getKey();
            if (!key.startsWith(prefix) || keep.contains(key.substring(prefix.length()))) {
                return false;
            }
            HotSpareDemand demand = entry.getValue();
            synchronized (demand) {
                return demand.target <= 0 && demand.consumedSinceLastPass == 0;
            }
        });
    }

    /**
     * Forgets every learned target. For tests, and for callers that have to reason about a cloud
     * whose configuration has been replaced.
     */
    public static void reset() {
        DEMANDS.clear();
    }

    private static String key(@NonNull EC2Cloud cloud, @NonNull String label) {
        return cloud.name + '\u0000' + label;
    }

    /**
     * Moves the target in response to what the label looks like right now: at least one step while
     * it is in use, the work in sight once that is larger, and a step more than that while the
     * spares are not keeping up.
     *
     * @param spares idle agents of the label group that could take work immediately.
     * @param provisioning instances of the label group on their way to becoming spares.
     * @param queued builds waiting for the label.
     * @param busy agents of the label group currently running a build.
     * @return the number of idle spares the label should hold.
     */
    public synchronized int updateTarget(
            @NonNull HotSpareConfigByLabel config, int spares, int provisioning, int queued, int busy) {
        return updateTarget(config, config.getBaseHotSpares(), spares, provisioning, queued, busy);
    }

    /**
     * The same, for a label the rule governs without naming. The count an admin asked to always be
     * held belongs to the rule as a whole, so it is charged to the label the rule names and the
     * labels underneath it start from nothing; charging it to each of them would multiply it by
     * however many labels the rule happens to cover.
     *
     * @param base the floor this label's target may not fall below.
     */
    public synchronized int updateTarget(
            @NonNull HotSpareConfigByLabel config, int base, int spares, int provisioning, int queued, int busy) {
        final int step = growthStep(config);
        final long now = clock.millis();
        final int consumed = consumedSinceLastPass;
        consumedSinceLastPass = 0;

        // The work the label has in sight: what is waiting for it and what it is already running.
        final int load = queued + busy;
        final boolean inUse = consumed > 0 || load > 0;

        /*
         * Two things say the spares are not keeping up: builds took the last of the pool, and builds
         * are waiting that neither the spares nor the instances already on their way can take.
         * Counting what is in flight is what makes this settle rather than chase a queue that
         * instances are already on their way to serve.
         */
        final boolean ranDry = consumed > 0 && spares == 0;
        final boolean queueUnserved = queued > spares + provisioning;

        target = Math.max(target, base);

        if (inUse) {
            quietSinceMillis = 0;

            /*
             * The load is a measurement, so it is followed at once: a build fanning out to 20
             * branches needs 20 spares now, not in four minutes' worth of steps. The step is the
             * floor rather than the rate, for the label that has only just been asked for anything.
             */
            int wanted = Math.max(step, load);

            if ((ranDry || queueUnserved) && now - lastGrowthMillis >= GROWTH_INTERVAL_MS) {
                /*
                 * The load is being served too slowly even so, so hold a step more than the load
                 * until it is not. One step per interval, because provisioning is not instant and a
                 * pass runs every time a build starts.
                 */
                lastGrowthMillis = now;
                wanted = Math.max(wanted, Math.min(load + step, target + step));
            }

            if (wanted > target) {
                raiseTo(config, wanted, consumed, spares, queued);
                overshotSinceMillis = 0;
            } else if (wanted < target) {
                /*
                 * The label is still in use but with less work than the target holds, so the peak it
                 * learned from an earlier burst has to fade. It fades a step at a time rather than
                 * dropping to the load, because the spares above the load are what the next burst
                 * lands on, and because the agents themselves are only worth giving up at the rate
                 * the admin set as the idle timeout.
                 */
                if (overshotSinceMillis == 0) {
                    overshotSinceMillis = now;
                } else if (now - overshotSinceMillis >= decayIntervalMillis(config)) {
                    overshotSinceMillis = now;
                    stepDownTo(
                            config,
                            base,
                            wanted,
                            "the label has held less work than the target for a whole idle timeout");
                }
            } else {
                overshotSinceMillis = 0;
            }
        } else {
            if (quietSinceMillis == 0) {
                quietSinceMillis = now;
                overshotSinceMillis = 0;
            } else if (now - quietSinceMillis >= decayIntervalMillis(config)) {
                quietSinceMillis = now;
                stepDownTo(config, base, base, "nothing has asked for the label for a whole idle timeout");
            }
        }

        target = Math.min(target, ceiling(config));
        return target;
    }

    private void raiseTo(HotSpareConfigByLabel config, int wanted, int consumed, int spares, int queued) {
        int raised = Math.min(ceiling(config), wanted);
        if (raised > target) {
            LOGGER.log(
                    Level.FINE,
                    "Raising the hot spare target for {0} from {1} to {2} ({3} taken since the last pass, "
                            + "{4} still warm, {5} queued)",
                    new Object[] {label, target, raised, consumed, spares, queued});
            target = raised;
        }
    }

    /**
     * Reports that a build has taken an executor on an agent of this label, which is both the
     * signal that the label is in use and the moment its pool of spares got one smaller.
     *
     * <p>The label is the one the build asked for, not the one of the rule that governs it: a build
     * that wanted {@code x86_64} says nothing about how warm the arm64 pool should be, even when
     * one rule covers both.
     *
     * <p>Callers follow this with {@link hudson.plugins.ec2.util.MinimumInstanceChecker#scheduleCheck()}
     * so the replacement is on its way while the build that took the spare is still starting, which
     * is the point of the whole feature: the next build finds somewhere warm to land instead of
     * waiting for an instance to boot.
     */
    public static void spareConsumed(@NonNull EC2Cloud cloud, @NonNull String label) {
        HotSpareDemand demand = of(cloud, label);
        synchronized (demand) {
            demand.consumedSinceLastPass++;
            demand.quietSinceMillis = 0;
        }
    }

    public synchronized int getTarget() {
        return target;
    }

    /**
     * Gives up one step of target, stopping at {@code floor} or the base count, whichever is
     * higher. The target is what decides how many spares a label keeps, so nothing terminates an
     * agent until this has come down past it.
     */
    private void stepDownTo(HotSpareConfigByLabel config, int base, int floor, String why) {
        int lowered = Math.max(Math.max(base, floor), target - growthStep(config));
        if (lowered != target) {
            LOGGER.log(Level.FINE, "Lowering the hot spare target for {0} from {1} to {2}: {3}", new Object[] {
                label, target, lowered, why
            });
            target = lowered;
        }
    }

    /**
     * @return the amount the target moves by. A rule configured with no step would never move, so
     *     it still creeps by one rather than freezing at its base count.
     */
    private static int growthStep(HotSpareConfigByLabel config) {
        return Math.max(1, config.getScalingFactor());
    }

    private static int ceiling(HotSpareConfigByLabel config) {
        Integer max = config.getMaxHotSpares();
        return max == null ? Integer.MAX_VALUE : Math.max(0, max);
    }

    /**
     * @return how long a label has to be quiet before it gives up a step. The idle timeout is the
     *     admin's own statement of how long an unused agent is worth keeping, so the prediction
     *     fades at the same rate the agents behind it do.
     */
    private static long decayIntervalMillis(HotSpareConfigByLabel config) {
        int minutes = Math.abs(config.getIdleTimeoutMinutes());
        if (minutes == 0) {
            // The agents are never idle-terminated, so there is no rate to follow. Fall back to the
            // default idle timeout, otherwise a target raised once would never come back down.
            minutes = HotSpareConfigByLabel.DEFAULT_IDLE_TIMEOUT_MINUTES;
        }
        return TimeUnit.MINUTES.toMillis(minutes);
    }
}
