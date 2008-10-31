package hudson.plugins.ec2;

import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * {@link RetentionStrategy} for EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2RetentionStrategy extends RetentionStrategy<EC2Computer> {
    @DataBoundConstructor
    public EC2RetentionStrategy() {
    }

    public synchronized long check(EC2Computer c) {
        if (c.isIdle()) {
            // TODO: really think about the right strategy here
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit.MINUTES.toMillis(30)) {
                LOGGER.info("Disconnecting "+c.getName());
                c.getNode().terminate();
            }
        }
        return 1;
    }

    public Descriptor<RetentionStrategy<?>> getDescriptor() {
        return DescriptorImpl.INSTANCE;
    }

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        public static final DescriptorImpl INSTANCE = new DescriptorImpl();

        private DescriptorImpl() {
            super(EC2RetentionStrategy.class);
        }

        public String getDisplayName() {
            return hudson.slaves.Messages.RetentionStrategy_Demand_displayName();
        }

        static {
        }
    }

    private static final Logger LOGGER = Logger.getLogger(EC2RetentionStrategy.class.getName());
}
