package hudson.plugins.ec2;

import static hudson.plugins.ec2.EC2Cloud.EC2_REQUEST_EXPIRED_ERROR_CODE;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;
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
            if (node instanceof EC2AbstractSlave) {
                final EC2AbstractSlave ec2Slave = (EC2AbstractSlave) node;
                try {
                    if (!ec2Slave.isAlive(true)) {
                        LOGGER.info("EC2 instance is dead: " + ec2Slave.getInstanceId());
                        try {
                            ec2Slave.terminate();
                        } catch (InterruptedException | IOException e) {
                            LOGGER.log(
                                    Level.WARNING, "Failed to terminate EC2 instance: " + ec2Slave.getInstanceId(), e);
                        }
                    }
                } catch (AmazonClientException e) {
                    if (e instanceof AmazonEC2Exception
                            && EC2_REQUEST_EXPIRED_ERROR_CODE.equals(((AmazonEC2Exception) e).getErrorCode())) {
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
