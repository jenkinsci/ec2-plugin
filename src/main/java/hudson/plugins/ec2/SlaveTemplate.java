package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.InstanceType;
import hudson.slaves.ComputerLauncher;
import hudson.model.Describable;
import hudson.model.Descriptor;

/**
 * Template of {@link EC2Slave} to launch.
 *
 * @author Kohsuke Kawaguchi
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {
    public final String ami;
    public final String remoteFS;
    public final InstanceType type;
    public final String label;
    public final ComputerLauncher launcher;

    public SlaveTemplate(String ami, String remoteFS, InstanceType type, String label, ComputerLauncher launcher) {
        this.ami = ami;
        this.remoteFS = remoteFS;
        this.type = type;
        this.label = label;
        this.launcher = launcher;
    }

    public DescriptorImpl getDescriptor() {
        return DescriptorImpl.INSTANCE;
    }

    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {
        public static final DescriptorImpl INSTANCE = new DescriptorImpl();
        private DescriptorImpl() {
            super(SlaveTemplate.class);
        }

        public String getDisplayName() {
            return null;
        }
    }
}
