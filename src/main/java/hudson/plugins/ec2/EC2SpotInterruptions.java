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
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The agents EC2 has taken back, by name, so the reason outlives the agent.
 *
 * <p>A build only finds out it has lost its agent once the channel stops answering, and by then the
 * node is usually gone, taking its offline cause with it. Anything asked afterwards why the build
 * failed - {@link EC2SpotInterruptionErrorCondition} in particular - has nothing left to read but
 * the name the build recorded when it claimed the workspace, so that name is what is kept here.
 *
 * <p>Entries are dropped after {@link #RETENTION_MILLIS}, which only has to outlast the gap between
 * an instance being reclaimed and the build noticing.
 */
@Restricted(NoExternalUse.class)
final class EC2SpotInterruptions {

    private static final Logger LOGGER = Logger.getLogger(EC2SpotInterruptions.class.getName());

    private static final long RETENTION_MILLIS =
            Long.getLong("jenkins.ec2.spotInterruptionMemoryMs", TimeUnit.HOURS.toMillis(1));

    /**
     * A ceiling on the entries kept, so a controller losing spot capacity faster than its builds
     * notice cannot grow this without bound. Reaching it costs the oldest entry, which is the one
     * whose build is least likely to still be waiting to find out.
     */
    private static final int MAX_ENTRIES = Integer.getInteger("jenkins.ec2.spotInterruptionMemoryEntries", 1000);

    private static final Map<String, Long> INTERRUPTED = new ConcurrentHashMap<>();

    static Clock clock = Clock.systemUTC();

    private EC2SpotInterruptions() {}

    /**
     * Records that EC2 took an agent back, whether that was read from the instance's own two minute
     * notice or from what EC2 said about the instance afterwards.
     *
     * @param nodeName the name of the Jenkins node, which is what a build has to go on later.
     */
    static void note(@NonNull String nodeName) {
        if (nodeName.isBlank()) {
            return;
        }
        INTERRUPTED.put(nodeName, clock.millis());
        LOGGER.log(Level.FINE, "Noted that {0} was lost to a spot interruption", nodeName);
        sweep();
    }

    /**
     * @return whether EC2 reclaimed the agent of this name recently enough to still be the
     *     explanation for a build that has just failed.
     */
    static boolean wasInterrupted(@NonNull String nodeName) {
        Long at = INTERRUPTED.get(nodeName);
        return at != null && clock.millis() - at <= RETENTION_MILLIS;
    }

    private static void sweep() {
        long cutoff = clock.millis() - RETENTION_MILLIS;
        INTERRUPTED.values().removeIf(at -> at < cutoff);
        while (INTERRUPTED.size() > MAX_ENTRIES) {
            Map.Entry<String, Long> oldest = INTERRUPTED.entrySet().stream()
                    .min(Comparator.comparingLong(Map.Entry::getValue))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            /*
             * Dropped whatever its timestamp says by now. Removing it only if the timestamp still
             * matches would leave the size unchanged when another thread has just refreshed it,
             * and this loop would go round again having freed nothing.
             */
            INTERRUPTED.remove(oldest.getKey());
        }
    }

    /** For tests. */
    static void reset() {
        INTERRUPTED.clear();
    }
}
