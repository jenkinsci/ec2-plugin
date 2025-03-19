package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

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
        MinimumInstanceChecker.checkForMinimumInstances();
    }

    private void removeDeadNodes() {
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof EC2AbstractSlave ec2Slave) {
                try {
                    if (!ec2Slave.isAlive(true)) {
                        LOGGER.info("EC2 instance is dead: " + ec2Slave.getInstanceId());
                        ec2Slave.terminate();
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
