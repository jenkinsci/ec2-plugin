package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.InstanceType;
import com.xerox.amazonws.ec2.Jec2;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Slave;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.NodeDescriptor;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Slave running on EC2.
 * 
 * @author Kohsuke Kawaguchi
 */
public final class EC2Slave extends Slave {
    /**
     * Comes from {@link SlaveTemplate#initScript}.
     */
    public final String initScript;

    public EC2Slave(String instanceId, String description, String remoteFS, InstanceType type, String label, String initScript) throws FormException, IOException {
        super(instanceId, description, remoteFS, toNumExecutors(type), Mode.NORMAL, label, new EC2UnixLauncher(), new EC2RetentionStrategy());
        this.initScript  = initScript;
    }

    /**
     * See http://aws.amazon.com/ec2/instance-types/
     */
    /*package*/ static int toNumExecutors(InstanceType it) {
        switch (it) {
        case DEFAULT:       return 1;
        case MEDIUM_HCPU:   return 5;
        case LARGE:         return 4;
        case XLARGE:        return 8;
        case XLARGE_HCPU:   return 20;
        default:            throw new AssertionError();
        }
    }

    /**
     * EC2 instance ID.
     */
    public String getInstanceId() {
        return getNodeName();
    }

    @Override
    public Computer createComputer() {
        return new EC2Computer(this);
    }

    /**
     * Terminates the instance in EC2.f
     */
    public void terminate() {
        Jec2 ec2 = EC2Cloud.get().connect();
        try {
            ec2.terminateInstances(Collections.singletonList(getInstanceId()));
            LOGGER.info("Terminated EC2 instance: "+getInstanceId());
            Hudson.getInstance().removeNode(this);
        } catch (EC2Exception e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        }
    }

    public static final class DescriptorImpl extends NodeDescriptor {
        public String getDisplayName() {
            return "Amazon EC2";
        }
    }

    private static final Logger LOGGER = Logger.getLogger(EC2Slave.class.getName());
}
