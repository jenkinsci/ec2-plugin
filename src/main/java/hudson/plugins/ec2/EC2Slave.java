package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Slave;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.NodeProperty;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

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
    public final String remoteAdmin; // e.g. 'ubuntu'
    public final String rootCommandPrefix; // e.g. 'sudo'
    public final String jvmopts; //e.g. -Xmx1g

    /**
     * For data read from old Hudson, this is 0, so we use that to indicate 22.
     */
    private final int sshPort;

    public EC2Slave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors, String labelString, String initScript, String remoteAdmin, String rootCommandPrefix, String jvmopts) throws FormException, IOException {
        this(instanceId, description, remoteFS, sshPort, numExecutors, Mode.NORMAL, labelString, initScript, Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts);
    }

    @DataBoundConstructor
    public EC2Slave(String instanceId, String description, String remoteFS, int sshPort, int numExecutors, Mode mode, String labelString, String initScript, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String rootCommandPrefix, String jvmopts) throws FormException, IOException {
        super(instanceId, description, remoteFS, numExecutors, mode, labelString, new EC2UnixLauncher(), new EC2RetentionStrategy(), nodeProperties);
        this.initScript  = initScript;
        this.remoteAdmin = remoteAdmin;
        this.rootCommandPrefix = rootCommandPrefix;
        this.jvmopts = jvmopts;
        this.sshPort = sshPort;
    }

    /**
     * Constructor for debugging.
     */
    public EC2Slave(String instanceId) throws FormException, IOException {
        this(instanceId,"debug","/tmp/hudson", 22, 1, Mode.NORMAL, "debug", "", Collections.<NodeProperty<?>>emptyList(), null, null, null);
    }

    /**
     * See http://aws.amazon.com/ec2/instance-types/
     */
    /*package*/ static int toNumExecutors(InstanceType it) {
        switch (it) {
        case T1Micro:       return 1;
        case M1Small:       return 1;
        case M1Large:       return 4;
        case C1Medium:      return 5;
        case M2Xlarge:      return 6;
        case M1Xlarge:      return 8;
        case M22xlarge:     return 13;
        case C1Xlarge:      return 20;
        case M24xlarge:     return 26;
        case Cc14xlarge:    return 33;
        case Cg14xlarge:    return 33;
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
     * Terminates the instance in EC2.
     */
    public void terminate() {
        try {
            AmazonEC2 ec2 = EC2Cloud.get().connect();
            TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(getInstanceId()));
            ec2.terminateInstances(request);
            LOGGER.info("Terminated EC2 instance: "+getInstanceId());
            Hudson.getInstance().removeNode(this);
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
        }
    }

    String getRemoteAdmin() {
        if (remoteAdmin == null || remoteAdmin.length() == 0)
            return "root";
        return remoteAdmin;
    }

    String getRootCommandPrefix() {
        if (rootCommandPrefix == null || rootCommandPrefix.length() == 0)
            return "";
        return rootCommandPrefix + " ";
    }

    String getJvmopts() {
        return Util.fixNull(jvmopts);
    }

    public int getSshPort() {
        return sshPort!=0 ? sshPort : 22;
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        @Override
		public String getDisplayName() {
            return "Amazon EC2";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(EC2Slave.class.getName());
}
