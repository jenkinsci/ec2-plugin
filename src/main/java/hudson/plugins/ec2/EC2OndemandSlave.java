package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.plugins.ec2.win.EC2WindowsLauncher;
import hudson.slaves.NodeProperty;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;

/**
 * Slave running on EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2OndemandSlave extends EC2AbstractSlave {
    private static final Logger LOGGER = Logger.getLogger(EC2OndemandSlave.class.getName());

    @Deprecated
    public EC2OndemandSlave(String instanceId, String templateDescription, String remoteFS, int numExecutors, String labelString, Mode mode, String initScript, String tmpDir, String remoteAdmin, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags, String cloudName, int launchTimeout, AMITypeData amiType)
            throws FormException, IOException {
        this(instanceId, templateDescription, remoteFS, numExecutors, labelString, mode, initScript, tmpDir, remoteAdmin, jvmopts, stopOnTerminate, idleTerminationMinutes, publicDNS, privateDNS, tags, cloudName, false, launchTimeout, amiType);
    }

    @Deprecated
    public EC2OndemandSlave(String instanceId, String templateDescription, String remoteFS, int numExecutors, String labelString, Mode mode, String initScript, String tmpDir, String remoteAdmin, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags, String cloudName, boolean useDedicatedTenancy, int launchTimeout, AMITypeData amiType)
            throws FormException, IOException {
        this(instanceId, templateDescription, remoteFS, numExecutors, labelString, mode, initScript, tmpDir, remoteAdmin, jvmopts, stopOnTerminate, idleTerminationMinutes, publicDNS, privateDNS, tags, cloudName, false, useDedicatedTenancy, launchTimeout, amiType);
    }

    @Deprecated
    public EC2OndemandSlave(String instanceId, String templateDescription, String remoteFS, int numExecutors, String labelString, Mode mode, String initScript, String tmpDir, String remoteAdmin, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags, String cloudName, boolean usePrivateDnsName, boolean useDedicatedTenancy, int launchTimeout, AMITypeData amiType)
            throws FormException, IOException {
        this(templateDescription + " (" + instanceId + ")", instanceId, templateDescription, remoteFS, numExecutors, labelString, mode, initScript, tmpDir, Collections.emptyList(), remoteAdmin, jvmopts, stopOnTerminate, idleTerminationMinutes, publicDNS, privateDNS, tags, cloudName, useDedicatedTenancy, false, launchTimeout, amiType, ConnectionStrategy.backwardsCompatible(usePrivateDnsName, false, false), -1);
    }

    @DataBoundConstructor
    public EC2OndemandSlave(String name, String instanceId, String templateDescription, String remoteFS, int numExecutors, String labelString, Mode mode, String initScript, String tmpDir, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, String publicDNS, String privateDNS, List<EC2Tag> tags, String cloudName, boolean useDedicatedTenancy, boolean useHostTenancy, int launchTimeout, AMITypeData amiType, ConnectionStrategy connectionStrategy, int maxTotalUses)
            throws FormException, IOException {

        super(name, instanceId, templateDescription, remoteFS, numExecutors, mode, labelString, amiType.isWindows() ? new EC2WindowsLauncher()
                : new EC2UnixLauncher(), new EC2RetentionStrategy(idleTerminationMinutes), initScript, tmpDir, nodeProperties, remoteAdmin, jvmopts, stopOnTerminate, idleTerminationMinutes, tags, cloudName, useDedicatedTenancy, launchTimeout, amiType, connectionStrategy, maxTotalUses);

        this.publicDNS = publicDNS;
        this.privateDNS = privateDNS;
        this.useHostTenancy = useHostTenancy;
    }

    /**
     * Constructor for debugging.
     */
    public EC2OndemandSlave(String instanceId) throws FormException, IOException {
        this(instanceId, instanceId, "debug", "/tmp/hudson", 1, "debug", Mode.NORMAL, "", "/tmp", Collections.emptyList(), null, null, false, null, "Fake public", "Fake private", null, null, false, false, 0, new UnixData(null, null, null, null), ConnectionStrategy.PRIVATE_IP, -1);
    }

    /**
     * Terminates the instance in EC2.
     */
    public void terminate() {
        if (terminateScheduled.getCount() == 0) {
            synchronized(terminateScheduled) {
                if (terminateScheduled.getCount() == 0) {
                    Computer.threadPoolForRemoting.submit(() -> {
                        try {
                            if (!isAlive(true)) {
                                /*
                                * The node has been killed externally, so we've nothing to do here
                                */
                                LOGGER.info("EC2 instance already terminated: " + getInstanceId());
                            } else {
                                AmazonEC2 ec2 = getCloud().connect();
                                TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(getInstanceId()));
                                ec2.terminateInstances(request);
                                LOGGER.info("Terminated EC2 instance (terminated): " + getInstanceId());
                            }
                            Jenkins.get().removeNode(this);
                            LOGGER.info("Removed EC2 instance from jenkins master: " + getInstanceId());
                        } catch (AmazonClientException | IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to terminate EC2 instance: " + getInstanceId(), e);
                        } finally {
                            synchronized(terminateScheduled) {
                                terminateScheduled.countDown();
                            }
                        }
                    });
                    terminateScheduled.reset();
                }
            }
        }
    }

    @Override
    public Node reconfigure(final StaplerRequest req, JSONObject form) throws FormException {
        if (form == null) {
            return null;
        }

        if (!isAlive(true)) {
            LOGGER.info("EC2 instance terminated externally: " + getInstanceId());
            try {
                Jenkins.get().removeNode(this);
            } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Attempt to reconfigure EC2 instance which has been externally terminated: "
                        + getInstanceId(), ioe);
            }

            return null;
        }

        return super.reconfigure(req, form);
    }

    @Extension
    public static final class DescriptorImpl extends EC2AbstractSlave.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return Messages.EC2OndemandSlave_AmazonEC2();
        }
    }

    @Override
    public String getEc2Type() {
        return Messages.EC2OndemandSlave_OnDemand();
    }
}
