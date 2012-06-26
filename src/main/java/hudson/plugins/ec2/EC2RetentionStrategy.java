package hudson.plugins.ec2;

import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;

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


    @DataBoundConstructor
    public EC2RetentionStrategy(String idleTerminationMinutes) {
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
	public synchronized long check(EC2Computer c) {

        /* If we've been told never to terminate, then we're done. */
        if  (idleTerminationMinutes == 0) return 1;

        if (c.isIdle() && !disabled) {
            // TODO: really think about the right strategy here
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleTerminationMinutes)) {
                LOGGER.info("Disconnecting "+c.getName());
                c.getNode().terminate();
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

    private static final Logger LOGGER = Logger.getLogger(EC2RetentionStrategy.class.getName());

    public static boolean disabled = Boolean.getBoolean(EC2RetentionStrategy.class.getName()+".disabled");
}
