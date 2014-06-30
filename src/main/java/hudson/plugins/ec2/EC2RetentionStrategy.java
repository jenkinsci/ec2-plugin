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

import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;

import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link RetentionStrategy} for EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2RetentionStrategy extends RetentionStrategy<EC2Computer> {
    /** Number of minutes of idleness before an instance should be terminated.
	    A value of zero indicates that the instance should never be automatically terminated */
    public final int idleTerminationMinutes;

    private transient ReentrantLock checkLock;

    @DataBoundConstructor
    public EC2RetentionStrategy(String idleTerminationMinutes) {
    	readResolve();
        if (idleTerminationMinutes == null || idleTerminationMinutes.trim() == "") {
            this.idleTerminationMinutes = 0;
        } else {
            int value = 30;
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
        if (! checkLock.tryLock()) {
            return 1;
        } else {
            try {
                return _check(c);
            } finally {
                checkLock.unlock();
            }
        }
    }

    private long _check(EC2Computer c) {

        /* If we've been told never to terminate, then we're done. */
        if  (idleTerminationMinutes == 0) {
        	return 1;
        }

        if (c.isIdle() && !disabled) {
            if (idleTerminationMinutes > 0) {
                // TODO: really think about the right strategy here
                final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
                if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleTerminationMinutes)) {
                    LOGGER.info("Idle timeout: "+c.getName());
                    c.getNode().idleTimeout();
                }
            } else {
                final long uptime;
                try {
                    uptime = c.getUptime();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                final int freeSecondsLeft = (60*60) - (int)(TimeUnit2.SECONDS.convert(uptime, TimeUnit2.MILLISECONDS) % (60*60));
                // if we have less "free" (aka already paid for) time left than our idle time, stop/terminate the instance
                if (freeSecondsLeft <= (Math.abs(idleTerminationMinutes*60))) {
                    LOGGER.info("Idle timeout: "+c.getName());
                    c.getNode().idleTimeout();
                }
            }
        }
        return 1;
    }

    /**
     * Try to connect to it ASAP.
     */
    @Override
    public void start(EC2Computer c) {
        c.connect(false);
    }

    // no registration since this retention strategy is used only for EC2 nodes that we provision automatically.
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

    private static final Logger LOGGER = Logger.getLogger(EC2RetentionStrategy.class.getName());

    public static boolean disabled = Boolean.getBoolean(EC2RetentionStrategy.class.getName()+".disabled");
}
