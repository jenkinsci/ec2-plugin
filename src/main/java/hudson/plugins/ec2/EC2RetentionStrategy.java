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

import com.amazonaws.AmazonClientException;
import hudson.init.InitMilestone;
import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.math.NumberUtils;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link RetentionStrategy} for EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2RetentionStrategy extends RetentionStrategy<EC2Computer> {
    private static final Logger LOGGER = Logger.getLogger(EC2RetentionStrategy.class.getName());

    public static final boolean DISABLED = Boolean.getBoolean(EC2RetentionStrategy.class.getName() + ".disabled");

    /**
     * Number of minutes of idleness before an instance should be terminated. A value of zero indicates that the
     * instance should never be automatically terminated. Negative values are times in remaining minutes before end of
     * billing period.
     */
    public final int idleTerminationMinutes;

    private transient ReentrantLock checkLock;
    private static final int STARTUP_TIME_DEFAULT_VALUE = 30;
    //ec2 instances charged by hour, time less than 1 hour is acceptable
    private static final int STARTUP_TIMEOUT = NumberUtils.toInt(
            System.getProperty(EC2RetentionStrategy.class.getCanonicalName() + ".startupTimeout",
                    String.valueOf(STARTUP_TIME_DEFAULT_VALUE)), STARTUP_TIME_DEFAULT_VALUE);

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

    @Override
    public long check(EC2Computer c) {
        if (!checkLock.tryLock()) {
            return 1;
        } else {
            try {
                return internalCheck(c);
            } finally {
                checkLock.unlock();
            }
        }
    }

    private long internalCheck(EC2Computer computer) {
        /*
        * If we've been told never to terminate, or node is null(deleted), no checks to perform
        */
        if (idleTerminationMinutes == 0 || computer.getNode() == null) {
            return 1;
        }


        if (computer.isIdle() && !DISABLED) {
            final long uptime;
            try {
                uptime = computer.getUptime();
            } catch (AmazonClientException | InterruptedException e) {
                // We'll just retry next time we test for idleness.
                LOGGER.fine("Exception while checking host uptime for " + computer.getName()
                        + ", will retry next check. Exception: " + e);
                return 1;
            }
            //on rare occasions, AWS may return fault instance which shows running in AWS console but can not be connected.
            //need terminate such fault instance by {@link #STARTUP_TIMEOUT}
            if (computer.isOffline() && uptime < TimeUnit2.MINUTES.toMillis(STARTUP_TIMEOUT)) {
                return 1;
            }
            final long idleMilliseconds = System.currentTimeMillis() - computer.getIdleStartMilliseconds();
            if (idleTerminationMinutes > 0) {
                // TODO: really think about the right strategy here, see
                // JENKINS-23792
                if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleTerminationMinutes)) {
                    LOGGER.info("Idle timeout of " + computer.getName() + " after "
                            + TimeUnit2.MILLISECONDS.toMinutes(idleMilliseconds) + " idle minutes");
                    computer.getNode().idleTimeout();
                }
            } else {
                final int freeSecondsLeft = (60 * 60)
                        - (int) (TimeUnit2.SECONDS.convert(uptime, TimeUnit2.MILLISECONDS) % (60 * 60));
                // if we have less "free" (aka already paid for) time left than
                // our idle time, stop/terminate the instance
                // See JENKINS-23821
                if (freeSecondsLeft <= TimeUnit.MINUTES.toSeconds(Math.abs(idleTerminationMinutes))) {
                    LOGGER.info("Idle timeout of " + computer.getName() + " after "
                            + TimeUnit2.MILLISECONDS.toMinutes(idleMilliseconds) + " idle minutes, with "
                            + TimeUnit2.SECONDS.toMinutes(freeSecondsLeft)
                            + " minutes remaining in billing period");
                    computer.getNode().idleTimeout();
                }
            }
        }
        return 1;
    }

    /**
     * Called when a new {@link EC2Computer} object is introduced (such as when Hudson started, or when
     * a new agent is added.)
     *
     * When Jenkins has just started, we don't want to spin up all the instances, so we only start if
     * the EC2 instance is already running
     */
    @Override
    public void start(EC2Computer c) {
        //Jenkins is in the process of starting up
        if (Jenkins.getActiveInstance().getInitLevel() != InitMilestone.COMPLETED) {
            InstanceState state = null;
            try {
                state = c.getState();
            } catch (AmazonClientException | InterruptedException e) {
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
        return this;
    }

}
