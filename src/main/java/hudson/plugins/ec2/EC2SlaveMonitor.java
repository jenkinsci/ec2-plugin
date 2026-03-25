package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;

/**
 * @author Bruno Meneguello
 */
@Extension
public class EC2SlaveMonitor extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(EC2SlaveMonitor.class.getName());

    private final Long recurrencePeriod;

    public EC2SlaveMonitor() {
        super("EC2 alive agents monitor");
        recurrencePeriod = Long.getLong("jenkins.ec2.checkAlivePeriod", TimeUnit.MINUTES.toMillis(10));
        LOGGER.log(Level.FINE, "EC2 check alive period is {0}ms", recurrencePeriod);
    }

    @Override
    public long getRecurrencePeriod() {
        return recurrencePeriod;
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        removeDeadNodes();
        MinimumInstanceChecker.scheduleCheck();
    }

    private void removeDeadNodes() {
        Map<EC2Cloud, List<EC2AbstractSlave>> byCloud = new HashMap<>();
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof EC2AbstractSlave ec2Slave) {
                String instanceId = ec2Slave.getInstanceId();
                if (StringUtils.isEmpty(instanceId)) {
                    continue;
                }
                EC2Cloud cloud = ec2Slave.getCloud();
                if (cloud == null) {
                    continue;
                }
                byCloud.computeIfAbsent(cloud, k -> new ArrayList<>()).add(ec2Slave);
            }
        }

        for (Map.Entry<EC2Cloud, List<EC2AbstractSlave>> entry : byCloud.entrySet()) {
            EC2Cloud cloud = entry.getKey();
            List<EC2AbstractSlave> slaves = entry.getValue();
            List<String> instanceIds = new ArrayList<>(slaves.size());
            for (EC2AbstractSlave s : slaves) {
                instanceIds.add(s.getInstanceId());
            }

            try {
                Map<String, Instance> instances = CloudHelper.getInstancesBatch(instanceIds, cloud);
                for (EC2AbstractSlave ec2Slave : slaves) {
                    try {
                        Instance inst = instances.get(ec2Slave.getInstanceId());
                        if (inst == null) {
                            LOGGER.info("EC2 instance not found (likely terminated): " + ec2Slave.getInstanceId());
                            ec2Slave.terminate();
                        } else if (InstanceStateName.TERMINATED.equals(inst.state().name())) {
                            LOGGER.info("EC2 instance is dead: " + ec2Slave.getInstanceId());
                            ec2Slave.terminate();
                        } else {
                            ec2Slave.updateFromFetchedInstance(inst);
                        }
                    } catch (SdkException e) {
                        if (e instanceof Ec2Exception
                                && EC2Cloud.EC2_REQUEST_EXPIRED_ERROR_CODE.equals(
                                        ((Ec2Exception) e).awsErrorDetails().errorCode())) {
                            LOGGER.info("EC2 request expired, skipping consideration of " + ec2Slave.getInstanceId()
                                    + " due to unknown state.");
                        } else {
                            LOGGER.info("EC2 instance is dead and failed to terminate: " + ec2Slave.getInstanceId());
                            removeNode(ec2Slave);
                        }
                    }
                }
            } catch (SdkException e) {
                LOGGER.log(Level.WARNING, "Batch describeInstances failed for cloud " + cloud.getName() + ", falling back to per-node check", e);
                for (EC2AbstractSlave ec2Slave : slaves) {
                    try {
                        if (!ec2Slave.isAlive(true)) {
                            LOGGER.info("EC2 instance is dead: " + ec2Slave.getInstanceId());
                            ec2Slave.terminate();
                        }
                    } catch (SdkException ex) {
                        if (ex instanceof Ec2Exception
                                && EC2Cloud.EC2_REQUEST_EXPIRED_ERROR_CODE.equals(
                                        ((Ec2Exception) ex).awsErrorDetails().errorCode())) {
                            LOGGER.info("EC2 request expired, skipping consideration of " + ec2Slave.getInstanceId()
                                    + " due to unknown state.");
                        } else {
                            LOGGER.info("EC2 instance is dead and failed to terminate: " + ec2Slave.getInstanceId());
                            removeNode(ec2Slave);
                        }
                    }
                }
            }
        }
    }

    private void removeNode(EC2AbstractSlave ec2Slave) {
        try {
            Jenkins.get().removeNode(ec2Slave);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to remove node: " + ec2Slave.getInstanceId());
        }
    }
}
