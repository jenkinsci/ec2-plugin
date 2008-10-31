package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.InstanceType;
import hudson.model.Descriptor.FormException;
import hudson.model.Slave;
import hudson.model.Computer;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeDescriptor;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slave running on EC2.
 * 
 * @author Kohsuke Kawaguchi
 */
public final class EC2Slave extends Slave {
    public EC2Slave(String instanceId, String description, String remoteFS, InstanceType type, String label, ComputerLauncher launcher) throws FormException {
        // TODO: retention policy for Amazon
        super(instanceId, description, remoteFS, toNumExecutors(type), Mode.NORMAL, label, launcher, new EC2RetentionStrategy());
    }

    /**
     * See http://aws.amazon.com/ec2/instance-types/
     */
    private static int toNumExecutors(InstanceType it) {
        switch (it) {
        case DEFAULT:       return 1;
        case MEDIUM_HCPU:   return 5;
        case LARGE:         return 4;
        case XLARGE:        return 8;
        case XLARGE_HCPU:   return 20;
        default:            throw new AssertionError();
        }
    }

    @Override
    public Computer createComputer() {
        return new EC2Computer(this);
    }

    public NodeDescriptor getDescriptor() {
        return DescriptorImpl.INSTANCE;
    }

    public static final class DescriptorImpl extends NodeDescriptor {
        public static final DescriptorImpl INSTANCE = new DescriptorImpl();

        private DescriptorImpl() {
            super(EC2Slave.class);
        }

        public String getDisplayName() {
            return "Amazon EC2";
        }
    }
}
