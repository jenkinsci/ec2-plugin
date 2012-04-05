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
    @DataBoundConstructor
    public EC2RetentionStrategy() {
    }

    @Override
	public synchronized long check(EC2Computer c) {
        if (c.isIdle() && !disabled) {
            // TODO: really think about the right strategy here
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            // EC2 instances are charged at ceil(hours), so we should not terminate it when not near an hour boundary.
            final long milliSecondsUptimeThisHour = c.getUptime() % TimeUnit2.MINUTES.toMillis(60);
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(30) && milliSecondsUptimeThisHour > TimeUnit2.MINUTES.toMillis(55)) {
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
