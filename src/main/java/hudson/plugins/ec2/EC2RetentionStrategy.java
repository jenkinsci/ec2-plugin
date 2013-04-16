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
import com.amazonaws.AmazonServiceException;
import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;


import java.io.IOException;
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
    public final int offlineTerminationMinutes;


    @DataBoundConstructor
    public EC2RetentionStrategy(String idleTerminationMinutes, String offlineTerminationMinutes) {
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
        if (offlineTerminationMinutes == null || offlineTerminationMinutes.trim() == "") {
            this.offlineTerminationMinutes = 0;
        } else {
            int value = 60;
            try {
                value = Integer.parseInt(offlineTerminationMinutes);
            } catch (NumberFormatException nfe) {
                LOGGER.info("Malformed default offlineTerminationMinutes value: " + offlineTerminationMinutes);
    }

            this.offlineTerminationMinutes = value;
        }

    }

    @Override
	public synchronized long check(EC2Computer c) {

        /* If we've been told never to terminate, then we're done. */
        if (idleTerminationMinutes == 0 && offlineTerminationMinutes == 0) return 1;

        try {
        final long upTime = c.getUptime();
        final long offlineTerminationMinutes1 = TimeUnit2.MINUTES.toMillis(offlineTerminationMinutes);
        final long idleMilliseconds1 = System.currentTimeMillis() - c.getIdleStartMilliseconds();
        System.out.println(c.getName() + " idle: " + idleMilliseconds1);
        
       /* If the boxes uptime if greater than offline time threshold is online but not idle exit */
        if (upTime > offlineTerminationMinutes1 && c.isOnline() && !c.isIdle()) {
            LOGGER.info("Node has been up for " + upTime + " ms" + c.getName());
            return 1;
        }

        if (offlineTerminationMinutes > 0 && c.isOffline()) {
            LOGGER.info("Node in an unexpectedly disabled or offline, checking if the node needs to be terminated : " + "Node uptime :" + upTime  + c.getName());
            LOGGER.info("Node offlineTime :" + offlineTerminationMinutes1 + c.getName());

            //determine if node is freshly spinning up
            if (upTime > offlineTerminationMinutes1) {
                LOGGER.info("Node has been up for " + upTime + " does need to be terminated : " + c.getName());
                c.getNode().termOfflineNode(c);
            } else {
                LOGGER.info("Node does not need to be terminated, may still be coming online : " + c.getName());
            }
        }

        if (c.isIdle() && c.isOnline() && !disabled) {
            // TODO: really think about the right strategy here
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleTerminationMinutes)) {
                LOGGER.info("Idle timeout: "+c.getName());
                c.getNode().idleTimeout();
            }
        }
        } catch (AmazonServiceException e) {
        } catch (InterruptedException e) {
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

    private static final Logger LOGGER = Logger.getLogger(EC2RetentionStrategy.class.getName());

    public static boolean disabled = Boolean.getBoolean(EC2RetentionStrategy.class.getName()+".disabled");
}
