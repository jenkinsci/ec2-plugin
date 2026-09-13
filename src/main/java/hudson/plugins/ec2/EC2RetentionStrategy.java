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
import hudson.init.InitMilestone;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import hudson.slaves.RetentionStrategy;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * {@link RetentionStrategy} for EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2RetentionStrategy extends RetentionStrategy<EC2Computer> implements ExecutorListener {
    private static final Logger LOGGER = Logger.getLogger(EC2RetentionStrategy.class.getName());

    /**
     * Executor for heavy retention work (EC2 API calls, idle timeout, reconnect).
     * Runs outside the Queue lock so the Queue can complete its periodic routine in under a second.
     * Package-private and non-final so tests can replace with a direct (same-thread) executor.
     */
    static ExecutorService HEAVY_WORK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "EC2RetentionStrategy-heavy");
        t.setDaemon(true);
        return t;
    });

    public static final boolean DISABLED = Boolean.getBoolean(EC2RetentionStrategy.class.getName() + ".disabled");

    private volatile long nextCheckAfter = -1;
    private transient Clock clock;

    /**
     * Number of minutes of idleness before an instance should be terminated. A value of zero indicates that the
     * instance should never be automatically terminated. Negative values are times in remaining minutes before end of
     * billing period.
     */
    public final int idleTerminationMinutes;

    private transient ReentrantLock checkLock;
    private static final int STARTUP_TIME_DEFAULT_VALUE = 30;

    private static final Integer CHECK_INTERVAL_MINUTES = Integer.getInteger("jenkins.ec2.checkIntervalMinutes", 1);

    @DataBoundConstructor
    public EC2RetentionStrategy(String idleTerminationMinutes) {
        readResolve();
        if (idleTerminationMinutes == null || idleTerminationMinutes.trim().isEmpty()) {
            this.idleTerminationMinutes = 0;
        } else {
            int value = STARTUP_TIME_DEFAULT_VALUE;
            try {
                value = Integer.parseInt(idleTerminationMinutes);
            } catch (NumberFormatException nfe) {
                LOGGER.info("Malformed default idleTermination value: " + idleTerminationMinutes);
            }

            this.idleTerminationMinutes = value;
        }
    }

    EC2RetentionStrategy(String idleTerminationMinutes, Clock clock, long nextCheckAfter) {
        this(idleTerminationMinutes);
        this.clock = clock;
        this.nextCheckAfter = nextCheckAfter;
    }

    long getNextCheckAfter() {
        return this.nextCheckAfter;
    }

    /**
     * Lightweight path: returns immediately so the Queue lock is not held during EC2 API calls.
     * Heavy work (getState, getUptime, idle timeout, reconnect) is scheduled to run asynchronously
     * outside the Queue lock. See docs/EC2_QUEUE_AUDIT.md.
     */
    @Override
    public long check(EC2Computer c) {
        if (!checkLock.tryLock()) {
            return CHECK_INTERVAL_MINUTES;
        }
        try {
            long currentTime = this.clock.millis();
            if (currentTime <= nextCheckAfter) {
                return CHECK_INTERVAL_MINUTES;
            }
            // Schedule heavy work to run outside the Queue lock; return immediately.
            nextCheckAfter = currentTime + TimeUnit.MINUTES.toMillis(CHECK_INTERVAL_MINUTES);
            final EC2Computer computer = c;
            HEAVY_WORK_EXECUTOR.execute(() -> runHeavyCheck(computer));
            return CHECK_INTERVAL_MINUTES;
        } finally {
            checkLock.unlock();
        }
    }

    /**
     * Runs outside the Queue lock. Performs EC2 API calls and retention logic.
     * State-changing operations (disconnect, terminate) use Queue.withLock for brief sections.
     */
    private void runHeavyCheck(EC2Computer computer) {
        if (!checkLock.tryLock()) {
            return;
        }
        try {
            attemptReconnectIfOffline(computer);
            internalCheck(computer);
        } finally {
            checkLock.unlock();
        }
    }

    private long internalCheck(EC2Computer computer) {
        /*
         * If the node is null (deleted), there is nothing left to check.
         */
        if (computer.getNode() == null) {
            return CHECK_INTERVAL_MINUTES;
        }

        /*
         * The effective values have to be resolved before any early return, because a template that
         * never idle-terminates may still have a grace period to enforce, and a label rule may
         * supply an idle timeout the template itself does not configure.
         */
        final int effectiveIdleMinutes = effectiveIdleTerminationMinutes(computer);
        final int graceMinutes = effectiveGracePeriodMinutes(computer);
        if (effectiveIdleMinutes == 0 && graceMinutes == 0) {
            return CHECK_INTERVAL_MINUTES;
        }

        /*
         * If we have equal or less number of agents than the template's minimum instance count, don't perform check.
         */
        SlaveTemplate slaveTemplate = computer.getSlaveTemplate();
        if (slaveTemplate != null) {
            long numberOfCurrentInstancesForTemplate = MinimumInstanceChecker.countCurrentNumberOfAgents(slaveTemplate);
            if (numberOfCurrentInstancesForTemplate > 0
                    && numberOfCurrentInstancesForTemplate <= slaveTemplate.getMinimumNumberOfInstances()) {
                // Check if we're in an active time-range for keeping minimum number of instances
                if (MinimumInstanceChecker.minimumInstancesActive(
                        slaveTemplate.getMinimumNumberOfInstancesTimeRangeConfig())) {
                    return CHECK_INTERVAL_MINUTES;
                }
            }
        }

        if (computer.isIdle() && !DISABLED) {
            final long uptime;
            final Instant launchedAt;
            InstanceState state;

            try {
                state = computer.getState(); // Get State before Uptime because getState will refresh the cached EC2
                // info
                uptime = computer.getUptime();
                launchedAt = computer.getLaunchTime();
            } catch (SdkException | InterruptedException e) {
                // We'll just retry next time we test for idleness.
                LOGGER.fine("Exception while checking host uptime for " + computer.getName()
                        + ", will retry next check. Exception: " + e);
                return CHECK_INTERVAL_MINUTES;
            }

            // Don't bother checking anything else if the instance is already in the desired state:
            // * Already Terminated
            // * We use stop-on-terminate and the instance is currently stopped or stopping
            if (InstanceState.TERMINATED.equals(state)
                    || (slaveTemplate != null && slaveTemplate.stopOnTerminate)
                            && (InstanceState.STOPPED.equals(state) || InstanceState.STOPPING.equals(state))) {
                if (computer.isOnline()) {
                    LOGGER.info("External Stop of " + computer.getName() + " detected - disconnecting. instance status "
                            + state);
                    try {
                        Queue.withLock(() -> computer.disconnect(null));
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE, "Error disconnecting " + computer.getName(), e);
                    }
                }
                return CHECK_INTERVAL_MINUTES;
            }

            /*
             * The grace period is measured from the moment provisioning was requested, so an agent
             * that never establishes a channel is dealt with even while its launcher is still
             * trying. The grace period and the launch timeout run independently and whichever
             * expires first wins, so an unexpired grace period defers to the launch-timeout branch
             * below instead of returning here.
             */
            boolean withinGracePeriod = false;
            if (graceMinutes > 0 && computer.getOnlineSinceMillis() == 0) {
                long provisionedAt = computer.getProvisionRequestedAtMillis();
                if (provisionedAt == 0) {
                    provisionedAt = launchedAt.toEpochMilli();
                }
                if (this.clock.millis() <= provisionedAt + TimeUnit.MINUTES.toMillis(graceMinutes)) {
                    withinGracePeriod = true;
                } else if (effectiveDiscardAfterGracePeriod(computer)) {
                    LOGGER.info("Grace period of " + computer.getName() + " expired after " + graceMinutes
                            + " minutes without the agent coming online, instance status " + state);
                    EC2AbstractSlave node = computer.getNode();
                    if (node != null) {
                        try {
                            Queue.withLock(node::graceTimeout);
                        } catch (Exception e) {
                            LOGGER.log(Level.FINE, "Error discarding after grace period for " + computer.getName(), e);
                        }
                    }
                    return CHECK_INTERVAL_MINUTES;
                }
                /*
                 * Expired without discarding: fall through so the idle clock runs from the grace
                 * deadline rather than from the EC2 launch time.
                 */
            }

            // on rare occasions, AWS may return fault instance which shows running in AWS console but can not be
            // connected.
            // need terminate such fault instance.
            // An instance may also fail running user data scripts and
            // need to be cleaned up.
            if (computer.isOffline()) {
                if (computer.isConnecting()) {
                    LOGGER.log(
                            Level.FINE,
                            "Computer {0} connecting and still offline, will check if the launch timeout has expired",
                            computer.getInstanceId());

                    EC2AbstractSlave node = computer.getNode();
                    if (Objects.isNull(node)) {
                        return CHECK_INTERVAL_MINUTES;
                    }
                    long launchTimeout = node.getLaunchTimeoutInMillis();
                    if (launchTimeout > 0 && uptime > launchTimeout) {
                        // Computer is offline and startup time has expired
                        LOGGER.info("Startup timeout of " + computer.getName() + " after "
                                + uptime + " milliseconds (timeout: "
                                + launchTimeout + " milliseconds), instance status: " + state.toString());
                        try {
                            Queue.withLock(node::launchTimeout);
                        } catch (Exception e) {
                            LOGGER.log(Level.FINE, "Error launching timeout for " + computer.getName(), e);
                        }
                    }
                    return CHECK_INTERVAL_MINUTES;
                } else {
                    LOGGER.log(
                            Level.FINE,
                            "Computer {0} offline but not connecting, will check if it should be terminated because of the idle time configured",
                            computer.getInstanceId());
                }
            }

            /*
             * An agent that has not come online yet and is still inside its grace period is left
             * alone: the idle clock only starts once the grace deadline has passed.
             */
            if (withinGracePeriod) {
                return CHECK_INTERVAL_MINUTES;
            }

            /*
             * Idle time is measured from the moment the agent became usable, not from when the EC2
             * instance was launched, so that slow user data or init scripts do not consume the idle
             * budget. See JENKINS-23792. An agent that never came online is measured from its grace
             * deadline when one is configured, and otherwise keeps the instance launch time as its
             * baseline.
             */
            long readyAtMillis = computer.getOnlineSinceMillis();
            if (readyAtMillis == 0) {
                readyAtMillis = graceMinutes > 0
                        ? computer.getProvisionRequestedAtMillis() + TimeUnit.MINUTES.toMillis(graceMinutes)
                        : launchedAt.toEpochMilli();
            }
            final long idleMilliseconds =
                    this.clock.millis() - Math.max(computer.getIdleStartMilliseconds(), readyAtMillis);

            if (effectiveIdleMinutes > 0) {
                boolean queueHasItemsForSlave;
                try {
                    queueHasItemsForSlave = Queue.withLock(() -> itemsInQueueForThisSlave(computer));
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error checking queue for " + computer.getName(), e);
                    queueHasItemsForSlave = true; // safe default: do not terminate
                }
                if (idleMilliseconds > TimeUnit.MINUTES.toMillis(effectiveIdleMinutes)
                        && !queueHasItemsForSlave
                        && !keptAsHotSpare(computer)) {

                    LOGGER.info("Idle timeout of " + computer.getName() + " after "
                            + TimeUnit.MILLISECONDS.toMinutes(idleMilliseconds) + " idle minutes, instance status"
                            + state.toString());
                    EC2AbstractSlave slaveNode = computer.getNode();
                    if (slaveNode != null) {
                        try {
                            Queue.withLock(slaveNode::idleTimeout);
                        } catch (Exception e) {
                            LOGGER.log(Level.FINE, "Error idle timeout for " + computer.getName(), e);
                        }
                    }
                }
            } else if (effectiveIdleMinutes < 0) {
                final int oneHourSeconds = (int) TimeUnit.SECONDS.convert(1, TimeUnit.HOURS);
                // AWS bills by the hour for EC2 Instances, so calculate the remaining seconds left in the "billing
                // hour"
                // Note: Since October 2017, this isn't true for Linux instances, but the logic hasn't yet been updated
                // for this
                final int freeSecondsLeft = oneHourSeconds
                        - (int) (TimeUnit.SECONDS.convert(uptime, TimeUnit.MILLISECONDS) % oneHourSeconds);
                // if we have less "free" (aka already paid for) time left than
                // our idle time, stop/terminate the instance
                // See JENKINS-23821
                boolean queueHasItemsForSlaveBilling;
                try {
                    queueHasItemsForSlaveBilling = Queue.withLock(() -> itemsInQueueForThisSlave(computer));
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error checking queue for " + computer.getName(), e);
                    queueHasItemsForSlaveBilling = true;
                }
                if (freeSecondsLeft <= TimeUnit.MINUTES.toSeconds(Math.abs(effectiveIdleMinutes))
                        && !queueHasItemsForSlaveBilling
                        && !keptAsHotSpare(computer)) {
                    LOGGER.info("Idle timeout of " + computer.getName() + " after "
                            + TimeUnit.MILLISECONDS.toMinutes(idleMilliseconds) + " idle minutes, with "
                            + TimeUnit.SECONDS.toMinutes(freeSecondsLeft)
                            + " minutes remaining in billing period");
                    EC2AbstractSlave slaveNode = computer.getNode();
                    if (slaveNode != null) {
                        try {
                            Queue.withLock(slaveNode::idleTimeout);
                        } catch (Exception e) {
                            LOGGER.log(Level.FINE, "Error idle timeout for " + computer.getName(), e);
                        }
                    }
                }
            }
        }
        return CHECK_INTERVAL_MINUTES;
    }

    /**
     * @return whether a hot spare rule is still counting on this agent, in which case the idle
     *     timeout leaves it alone. The target is the authority on how many spares a label holds, so
     *     an agent is only reclaimed once the target has come down past it; letting the timeout
     *     reclaim it first would just have the next pass provision a replacement.
     */
    private static boolean keptAsHotSpare(EC2Computer computer) {
        EC2Cloud cloud = cloudOf(computer);
        if (cloud == null) {
            return false;
        }
        if (templateOf(computer) == null) {
            return false;
        }
        if (MinimumInstanceChecker.isSpareStillWanted(cloud, computer)) {
            LOGGER.log(
                    Level.FINE,
                    "Keeping {0} past its idle timeout: the hot spares of a label it serves are still counting on it",
                    computer.getName());
            return true;
        }
        return false;
    }

    /**
     * Tells the hot spare prediction that this agent has just stopped being a spare, and asks for a
     * replacement to be provisioned now rather than at the next periodic pass.
     *
     * <p>Only the bookkeeping happens here. Provisioning is left to
     * {@link MinimumInstanceChecker#scheduleCheck()} because this runs on the executor thread, which
     * is no place to wait on EC2.
     */
    private static void noteConsumedSpare(EC2Computer computer, Queue.Task task) {
        EC2Cloud cloud = cloudOf(computer);
        if (cloud == null) {
            return;
        }
        /*
         * The pool that just lost a spare is the one the build asked for, not every pool the agent
         * belongs to: a build that wanted x86 hardware is no reason to keep arm64 agents warm, even
         * where one rule covers both labels.
         */
        Label assigned = task == null ? null : task.getAssignedLabel();
        if (assigned == null || cloud.getHotSpareConfigForLabel(assigned) == null) {
            return;
        }
        HotSpareDemand.spareConsumed(cloud, assigned.getName());
        MinimumInstanceChecker.scheduleCheck();
    }

    /**
     * @return the idle termination in minutes that applies to this computer. Zero means the agent
     *     is never idle-terminated, negative values are minutes remaining in the billing period.
     */
    private int effectiveIdleTerminationMinutes(EC2Computer computer) {
        EC2Cloud cloud = cloudOf(computer);
        if (cloud != null) {
            Integer fromLabelRule = cloud.resolveIdleTerminationMinutes(computer);
            if (fromLabelRule != null) {
                return fromLabelRule;
            }
        }
        return idleTerminationMinutes;
    }

    /**
     * @return the grace period in minutes that applies to this computer, or 0 when none is
     *     configured.
     */
    private int effectiveGracePeriodMinutes(EC2Computer computer) {
        EC2Cloud cloud = cloudOf(computer);
        if (cloud != null) {
            return cloud.resolveGracePeriodMinutes(computer);
        }
        SlaveTemplate template = computer.getSlaveTemplate();
        return template == null ? 0 : template.getGracePeriodMinutes();
    }

    /**
     * @return whether a computer still offline at the end of its grace period is discarded rather
     *     than handed over to the idle-termination clock.
     */
    private boolean effectiveDiscardAfterGracePeriod(EC2Computer computer) {
        EC2Cloud cloud = cloudOf(computer);
        if (cloud != null) {
            return cloud.resolveDiscardAfterGracePeriod(computer);
        }
        SlaveTemplate template = computer.getSlaveTemplate();
        return template == null || template.isDiscardAfterGracePeriod();
    }

    /**
     * The agent's template, or null for an agent whose cloud or template no longer exists. Resolving
     * one goes through the cloud, so an agent left behind by a configuration change can fail here
     * and must not take a build start down with it.
     */
    @CheckForNull
    private static SlaveTemplate templateOf(EC2Computer computer) {
        try {
            return computer.getSlaveTemplate();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Template not resolvable for " + computer.getName(), e);
            return null;
        }
    }

    @CheckForNull
    private static EC2Cloud cloudOf(EC2Computer computer) {
        EC2AbstractSlave node = computer.getNode();
        if (node == null) {
            return null;
        }
        try {
            return node.getCloud();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Cloud not resolvable for " + computer.getName(), e);
            return null;
        }
    }

    /**
     * Try to reconnect the EC2 Instance if it's offline but the status is running.
     * This could mean unstable ssh connection, so instead of failing the build,
     * we try to reconnect as soon as the EC2 Instance is running again.
     */
    private void attemptReconnectIfOffline(EC2Computer computer) {
        try {
            if (computer.isOffline() && !computer.isIdle() && computer.getState() == InstanceState.RUNNING) {
                LOGGER.warning("EC2Computer " + computer.getName() + " is offline");
                if (!computer.isConnecting()) {
                    // Keep retrying connection to agent until the job times out
                    LOGGER.warning("Attempting to reconnect EC2Computer " + computer.getName());
                    try {
                        computer.connect(false);
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE, "Error reconnecting " + computer.getName(), e);
                    }
                }
            }
        } catch (SdkException | InterruptedException e) {
            LOGGER.log(Level.FINE, "Error getting EC2 instance state for " + computer.getName(), e);
        }
    }

    /*
     * Checks if there are any items in the queue that are waiting for this node explicitly.
     * This prevents a node from being taken offline while there are Ivy/Maven Modules waiting to build.
     * Need to check entire queue as some modules may be blocked by upstream dependencies.
     * Accessing the queue in this way can block other threads, so only perform this check just prior
     * to timing out the slave.
     */
    private boolean itemsInQueueForThisSlave(EC2Computer c) {
        final EC2AbstractSlave selfNode = c.getNode();
        /* null checking is required here because in the event that a computer
         * doesn't have a node it will return null. In this case we want to
         * return false because there's no slave to prevent a timeout of.
         */
        if (selfNode == null) {
            return false;
        }
        final Label selfLabel = selfNode.getSelfLabel();
        Queue.Item[] items = Jenkins.get().getQueue().getItems();
        for (Queue.Item item : items) {
            final Label assignedLabel = item.getAssignedLabel();
            if (assignedLabel == selfLabel) {
                LOGGER.fine("Preventing idle timeout of " + c.getName()
                        + " as there is at least one item in the queue explicitly waiting for this slave");
                return true;
            }
        }
        return false;
    }

    /**
     * Lightweight path: returns immediately. Heavy work (getState, connect) runs asynchronously
     * so the caller is not blocked by EC2 API calls.
     */
    @Override
    public void start(EC2Computer c) {
        final EC2Computer computer = c;
        HEAVY_WORK_EXECUTOR.execute(() -> {
            if (Jenkins.get().getInitLevel() != InitMilestone.COMPLETED) {
                InstanceState state = null;
                try {
                    state = computer.getState();
                } catch (SdkException | InterruptedException e) {
                    LOGGER.log(Level.FINE, "Error getting EC2 instance state for " + computer.getName(), e);
                }
                if (!(InstanceState.PENDING.equals(state) || InstanceState.RUNNING.equals(state))) {
                    LOGGER.info("Ignoring start request for " + computer.getName()
                            + " during Jenkins startup due to EC2 instance state of " + state);
                    return;
                }
            }
            LOGGER.info("Start requested for " + computer.getName());
            try {
                computer.connect(false);
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Error connecting " + computer.getName(), e);
            }
        });
    }

    // no registration since this retention strategy is used only for EC2 nodes
    // that we provision automatically.
    // @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "EC2";
        }
    }

    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        clock = Clock.systemUTC();
        return this;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        EC2Computer computer = (EC2Computer) executor.getOwner();
        if (computer != null) {
            noteConsumedSpare(computer, task);
            EC2AbstractSlave slaveNode = computer.getNode();
            if (slaveNode != null) {
                int maxTotalUses = slaveNode.maxTotalUses;
                if (maxTotalUses <= -1) {
                    LOGGER.fine("maxTotalUses set to unlimited (" + slaveNode.maxTotalUses + ") for agent "
                            + slaveNode.instanceId);
                } else if (maxTotalUses <= 1) {
                    LOGGER.info("maxTotalUses drained - suspending agent " + slaveNode.instanceId);
                    computer.setAcceptingTasks(false);
                    MinimumInstanceChecker.scheduleCheck();
                } else {
                    slaveNode.maxTotalUses = slaveNode.maxTotalUses - 1;
                    LOGGER.info("Agent " + slaveNode.instanceId + " has " + slaveNode.maxTotalUses + " builds left");
                }
            }
        }
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        postJobAction(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        postJobAction(executor);
    }

    private void postJobAction(Executor executor) {
        EC2Computer computer = (EC2Computer) executor.getOwner();
        if (computer != null) {
            EC2AbstractSlave slaveNode = computer.getNode();
            if (slaveNode != null) {
                // At this point, if agent is in suspended state and has 1 last executer running, it is safe to
                // terminate.
                if (computer.countBusy() <= 1 && !computer.isAcceptingTasks()) {
                    LOGGER.info("Agent " + slaveNode.instanceId + " is terminated due to maxTotalUses ("
                            + slaveNode.maxTotalUses + ")");
                    slaveNode.terminate();
                } else {
                    if (slaveNode.maxTotalUses == 1) {
                        LOGGER.info("Agent " + slaveNode.instanceId + " is still in use by more than one ("
                                + computer.countBusy() + ") executers.");
                    }
                }
            }
        }
    }
}
