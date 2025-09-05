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

    public static final boolean DISABLED = Boolean.getBoolean(EC2RetentionStrategy.class.getName() + ".disabled");

    private long nextCheckAfter = -1;
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

    @Override
    public long check(EC2Computer c) {
        if (!checkLock.tryLock()) {
            return CHECK_INTERVAL_MINUTES;
        } else {
            try {
                long currentTime = this.clock.millis();

                if (currentTime > nextCheckAfter) {
                    long intervalMins = internalCheck(c);
                    nextCheckAfter = currentTime + TimeUnit.MINUTES.toMillis(intervalMins);
                    return intervalMins;
                } else {
                    return CHECK_INTERVAL_MINUTES;
                }
            } finally {
                checkLock.unlock();
            }
        }
    }

    private long internalCheck(EC2Computer computer) {
        /*
         * If we've been told never to terminate, or node is null(deleted), no checks to perform, or retention strategy disabled
         */
        if (idleTerminationMinutes == 0 || computer.getNode() == null || DISABLED) {
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

        if (computer.isIdle()) {
            // Don't bother checking anything else if the instance is already in the desired state:
            // * Already Terminated
            // * We use stop-on-terminate and the instance is currently stopped or stopping
            if (InstanceState.TERMINATED.equals(state)
                    || (slaveTemplate != null && slaveTemplate.stopOnTerminate)
                            && (InstanceState.STOPPED.equals(state) || InstanceState.STOPPING.equals(state))) {
                if (computer.isOnline()) {
                    LOGGER.info("External Stop of " + computer.getName() + " detected - disconnecting. instance status"
                            + state);
                    computer.disconnect(null);
                }
                return CHECK_INTERVAL_MINUTES;
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
                        node.launchTimeout();
                    }
                    return CHECK_INTERVAL_MINUTES;
                } else {
                    LOGGER.log(
                            Level.FINE,
                            "Computer {0} offline but not connecting, will check if it should be terminated because of the idle time configured",
                            computer.getInstanceId());
                }
            }

            final long idleMilliseconds =
                    this.clock.millis() - Math.max(computer.getIdleStartMilliseconds(), launchedAt.toEpochMilli());

            if (idleTerminationMinutes > 0) {
                // TODO: really think about the right strategy here, see
                // JENKINS-23792

                if (idleMilliseconds > TimeUnit.MINUTES.toMillis(idleTerminationMinutes)
                        && !itemsInQueueForThisSlave(computer)) {

                    LOGGER.info("Idle timeout of " + computer.getName() + " after "
                            + TimeUnit.MILLISECONDS.toMinutes(idleMilliseconds) + " idle minutes, instance status"
                            + state.toString());
                    EC2AbstractSlave slaveNode = computer.getNode();
                    if (slaveNode != null) {
                        slaveNode.idleTimeout();
                    }
                }
            } else {
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
                if (freeSecondsLeft <= TimeUnit.MINUTES.toSeconds(Math.abs(idleTerminationMinutes))
                        && !itemsInQueueForThisSlave(computer)) {
                    LOGGER.info("Idle timeout of " + computer.getName() + " after "
                            + TimeUnit.MILLISECONDS.toMinutes(idleMilliseconds) + " idle minutes, with "
                            + TimeUnit.SECONDS.toMinutes(freeSecondsLeft)
                            + " minutes remaining in billing period");
                    EC2AbstractSlave slaveNode = computer.getNode();
                    if (slaveNode != null) {
                        slaveNode.idleTimeout();
                    }
                }
            }
        }

        if (computer.isOffline()) {
            if (!computer.isConnecting()) {
                // Retry connection to agent until the job times out
                // see: https://github.com/jenkinsci/workflow-durable-task-step-plugin/pull/463
                LOGGER.warning("Attempting to reconnect EC2Computer " + computer.getName());
                computer.connect(false);
            }
        }

        return CHECK_INTERVAL_MINUTES;
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
     * Called when a new {@link EC2Computer} object is introduced (such as when Hudson started, or when
     * a new agent is added.)
     * <p>
     * When Jenkins has just started, we don't want to spin up all the instances, so we only start if
     * the EC2 instance is already running
     */
    @Override
    public void start(EC2Computer c) {
        // Jenkins is in the process of starting up
        if (Jenkins.get().getInitLevel() != InitMilestone.COMPLETED) {
            InstanceState state = null;
            try {
                state = c.getState();
            } catch (SdkException | InterruptedException e) {
                LOGGER.log(Level.FINE, "Error getting EC2 instance state for " + c.getName(), e);
            }
            if (!(InstanceState.PENDING.equals(state) || InstanceState.RUNNING.equals(state))) {
                LOGGER.info("Ignoring start request for " + c.getName()
                        + " during Jenkins startup due to EC2 instance state of " + state);
                return;
            }
        }

        LOGGER.info("Start requested for " + c.getName());
        c.connect(false);
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
            EC2AbstractSlave slaveNode = computer.getNode();
            if (slaveNode != null) {
                int maxTotalUses = slaveNode.maxTotalUses;
                if (maxTotalUses <= -1) {
                    LOGGER.fine("maxTotalUses set to unlimited (" + slaveNode.maxTotalUses + ") for agent "
                            + slaveNode.instanceId);
                } else if (maxTotalUses <= 1) {
                    LOGGER.info("maxTotalUses drained - suspending agent " + slaveNode.instanceId);
                    computer.setAcceptingTasks(false);
                    MinimumInstanceChecker.checkForMinimumInstances();
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
