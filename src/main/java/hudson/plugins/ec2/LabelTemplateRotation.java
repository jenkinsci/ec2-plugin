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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Rotates the {@link SlaveTemplate}s of an {@link EC2Cloud} that match the same label.
 *
 * <p>Templates matching one label are treated as homogeneous: the admin gave them the same label,
 * so they are interchangeable instance types for the work that label describes. Rotating between
 * them lets provisioning fall back to different hardware immediately instead of retrying a single
 * template that has no capacity.
 *
 * <p>Selection is smooth weighted round-robin (as used by nginx) over
 * {@link SlaveTemplate#getHotSpareWeight()}: for weights 2 and 1 the leading template alternates as
 * {@code a, a, b, a, a, b} rather than picking {@code a} twice in a row and then {@code b} twice,
 * which is what makes the fallback fast when one instance type runs dry. A weight is therefore a
 * share of the attempts, and every weight keeps getting some of them.
 *
 * <p>{@link EC2Cloud#isSaturateHighestWeightFirst()} swaps that for a hierarchy: templates sharing
 * a weight form a band, the highest band leads every request, and a lower band is only reached once
 * every template above it is at its instance cap or in the capacity cooldown. Templates within a
 * band still rotate, so a band spreads over its instance types and availability zones.
 *
 * <p>All of this is inert unless {@link EC2Cloud#isRoundRobinTemplatesByLabel()} is enabled.
 */
@Restricted(NoExternalUse.class)
class LabelTemplateRotation {

    private static final Logger LOGGER = Logger.getLogger(LabelTemplateRotation.class.getName());

    /**
     * Default period, in milliseconds, during which a template is demoted in the rotation for a
     * label after it reported an insufficient-capacity error.
     */
    static final long DEFAULT_TEMPLATE_CAPACITY_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /**
     * How long (ms) a template is considered capacity-unavailable after an insufficient-capacity
     * error. Configurable via the system property
     * {@code hudson.plugins.ec2.LabelTemplateRotation.templateCapacityCooldownMillis}. Deliberately
     * separate from the per-subnet cooldown in {@link SlaveTemplate} so the two layers can be tuned
     * independently, while defaulting to the same five minutes.
     */
    private static final long TEMPLATE_CAPACITY_COOLDOWN_MILLIS = SystemProperties.getLong(
            LabelTemplateRotation.class.getName() + ".templateCapacityCooldownMillis",
            DEFAULT_TEMPLATE_CAPACITY_COOLDOWN_MILLIS);

    /** Smooth weighted round-robin state: label name to template key to current weight. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> currentWeights =
            new ConcurrentHashMap<>();

    /** Plain rotation cursor per label, used when every candidate has a weight of zero. */
    private final ConcurrentHashMap<String, Integer> unweightedCursor = new ConcurrentHashMap<>();

    /** Rotation cursor per label and weight, used within a band when saturating the highest first. */
    private final ConcurrentHashMap<String, Integer> bandCursor = new ConcurrentHashMap<>();

    /** Template key to the epoch-millis timestamp until which the template should be demoted. */
    private final ConcurrentHashMap<String, Long> templateCapacityCooldownUntil = new ConcurrentHashMap<>();

    private final BooleanSupplier enabled;

    private final BooleanSupplier saturateHighestWeightFirst;

    private Clock clock;

    LabelTemplateRotation(@NonNull BooleanSupplier enabled, @NonNull BooleanSupplier saturateHighestWeightFirst) {
        this.enabled = enabled;
        this.saturateHighestWeightFirst = saturateHighestWeightFirst;
    }

    /**
     * Orders the templates matching a label for provisioning. Templates currently in a capacity
     * cooldown are moved to the end rather than dropped, so a launch is still attempted when every
     * template is cooling down.
     *
     * @param labelName the label being provisioned for, or an empty string for no label
     * @param matching the templates matching that label, in configured order
     * @return the templates to try, best first, by share of the weights or by weight band
     *     depending on {@link EC2Cloud#isSaturateHighestWeightFirst()}. The configured order is
     *     returned unchanged when rotation is disabled or only one template matches.
     */
    List<SlaveTemplate> order(String labelName, Collection<SlaveTemplate> matching) {
        List<SlaveTemplate> candidates = new ArrayList<>(matching);
        if (candidates.size() <= 1 || !enabled.getAsBoolean()) {
            return candidates;
        }

        List<SlaveTemplate> available = new ArrayList<>(candidates.size());
        List<SlaveTemplate> coolingDown = new ArrayList<>();
        for (SlaveTemplate t : candidates) {
            if (isTemplateInCooldown(t, candidates.size())) {
                coolingDown.add(t);
            } else {
                available.add(t);
            }
        }

        // Every template is cooling down: rotate over all of them rather than refuse to provision.
        List<SlaveTemplate> pool = available.isEmpty() ? candidates : available;
        List<SlaveTemplate> ordered = saturateHighestWeightFirst.getAsBoolean()
                ? orderByWeightBand(labelName, pool)
                : rotateAround(pool, selectWeighted(labelName, pool));
        if (pool != candidates) {
            ordered.addAll(coolingDown);
        }
        return ordered;
    }

    /**
     * Orders the pool into weight bands, heaviest first, so a lighter band is only reached once
     * every template above it has turned the request down. Cooling-down templates are already out
     * of the pool, which is what lets a fully saturated band hand the label to the next one.
     *
     * <p>The heaviest band is rotated so its templates take turns leading, spreading a label over
     * the instance types and availability zones the admin considers equivalent. Lighter bands keep
     * their configured order: they are a fallback within this request, and they rotate in their own
     * right once they are the heaviest band still available.
     */
    private List<SlaveTemplate> orderByWeightBand(String labelName, List<SlaveTemplate> pool) {
        NavigableMap<Integer, List<SlaveTemplate>> bands = new TreeMap<>(Comparator.reverseOrder());
        for (SlaveTemplate t : pool) {
            bands.computeIfAbsent(t.getHotSpareWeight(), weight -> new ArrayList<>())
                    .add(t);
        }

        List<SlaveTemplate> ordered = new ArrayList<>(pool.size());
        boolean leading = true;
        for (Map.Entry<Integer, List<SlaveTemplate>> band : bands.entrySet()) {
            List<SlaveTemplate> members = band.getValue();
            if (leading && members.size() > 1) {
                int cursor = bandCursor.merge(key(labelName) + "/" + band.getKey(), 1, Integer::sum) - 1;
                ordered.addAll(rotateAround(members, members.get(Math.floorMod(cursor, members.size()))));
            } else {
                ordered.addAll(members);
            }
            leading = false;
        }
        return ordered;
    }

    /**
     * @return {@code pool} rotated so {@code head} leads and the templates configured before it
     *     wrap around to the end.
     */
    private static List<SlaveTemplate> rotateAround(List<SlaveTemplate> pool, SlaveTemplate head) {
        List<SlaveTemplate> ordered = new ArrayList<>(pool.size());
        int headIndex = Math.max(0, pool.indexOf(head));
        for (int i = 0; i < pool.size(); i++) {
            ordered.add(pool.get((headIndex + i) % pool.size()));
        }
        return ordered;
    }

    /**
     * Advances the rotation for a label by one pick and returns the winner.
     */
    private SlaveTemplate selectWeighted(String labelName, List<SlaveTemplate> pool) {
        int totalWeight = 0;
        for (SlaveTemplate t : pool) {
            totalWeight += t.getHotSpareWeight();
        }
        if (totalWeight == 0) {
            /*
             * Nothing carries any weight, which only happens when an admin has set every template
             * in the group to zero. Fall back to plain rotation so the label still provisions.
             */
            int cursor = unweightedCursor.merge(key(labelName), 1, Integer::sum) - 1;
            return pool.get(Math.floorMod(cursor, pool.size()));
        }

        Map<String, Integer> current = currentWeights.computeIfAbsent(key(labelName), k -> new ConcurrentHashMap<>());
        SlaveTemplate best = null;
        String bestKey = null;
        int bestCurrent = Integer.MIN_VALUE;
        for (SlaveTemplate t : pool) {
            String templateKey = templateKey(t);
            int updated = current.merge(templateKey, t.getHotSpareWeight(), Integer::sum);
            if (updated > bestCurrent) {
                bestCurrent = updated;
                best = t;
                bestKey = templateKey;
            }
        }
        current.merge(bestKey, -totalWeight, Integer::sum);
        return best;
    }

    /**
     * Marks a template as capacity-unavailable for the label group it was selected from.
     *
     * <p>No-op when {@code groupSize <= 1}: a label with a single template has nothing to fail over
     * to, so it must keep retrying across its own availability zones rather than being taken out of
     * rotation for the cooldown period.
     */
    void markTemplateUnavailable(SlaveTemplate t, int groupSize) {
        if (t == null || groupSize <= 1 || !enabled.getAsBoolean()) {
            return;
        }
        long until = getClock().millis() + TEMPLATE_CAPACITY_COOLDOWN_MILLIS;
        templateCapacityCooldownUntil.put(templateKey(t), until);
        LOGGER.log(
                Level.FINE,
                () -> String.format(
                        "Demoting template %s (instance type %s) in the label rotation until %d",
                        t.getDescription(), t.getType(), until));
    }

    /**
     * @return {@code true} if the template is currently demoted for a capacity error. Expired
     *     cooldowns are cleaned up as a side effect.
     */
    boolean isTemplateInCooldown(SlaveTemplate t, int groupSize) {
        if (t == null || groupSize <= 1 || !enabled.getAsBoolean()) {
            return false;
        }
        String templateKey = templateKey(t);
        Long until = templateCapacityCooldownUntil.get(templateKey);
        if (until == null) {
            return false;
        }
        if (getClock().millis() >= until) {
            templateCapacityCooldownUntil.remove(templateKey, until);
            return false;
        }
        return true;
    }

    /**
     * Overrides the clock used for cooldown bookkeeping. For tests only.
     */
    void setClock(Clock clock) {
        synchronized (this) {
            this.clock = clock;
        }
    }

    private synchronized Clock getClock() {
        if (clock == null) {
            clock = Clock.systemUTC();
        }
        return clock;
    }

    private static String key(String labelName) {
        return labelName == null ? "" : labelName;
    }

    /**
     * Identifies a template across configuration reloads. Descriptions are unique within a cloud
     * (see {@link EC2Cloud#addTemplate}) and are what the rest of the plugin counts instances by.
     */
    private static String templateKey(SlaveTemplate t) {
        String description = t.getDescription();
        if (description != null && !description.isBlank()) {
            return description;
        }
        return t.getAmi() + "/" + t.getType();
    }
}
