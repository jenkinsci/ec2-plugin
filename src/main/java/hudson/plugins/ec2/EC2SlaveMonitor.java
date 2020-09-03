package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

/**
 * @author Bruno Meneguello
 */
@Extension
public class EC2SlaveMonitor extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(EC2SlaveMonitor.class.getName());

    private final Long recurrencePeriod;

    public EC2SlaveMonitor() {
        super("EC2 alive slaves monitor");
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
            if (node instanceof EC2AbstractSlave && isCheckable(node)) {
                final EC2AbstractSlave ec2Slave = (EC2AbstractSlave) node;
                try {
                    if (!ec2Slave.isAlive(true)) {
                        LOGGER.info("EC2 instance is dead: " + ec2Slave.getInstanceId());
                        ec2Slave.terminate();
                    }
                } catch (AmazonClientException e) {
                    LOGGER.info("EC2 instance is dead and failed to terminate: " + ec2Slave.getInstanceId());
                    removeNode(ec2Slave);
                }
            }
        }
    }

    /**
     * A node is considered checkable if all executors are idle or if we are unable to determine if the executors are idle.
     *
     * @param node The node to evaluate
     * @return true if the node is checkable, false if the node has at least one non-idle executor
     */
    private boolean isCheckable(Node node) {
        boolean result = false;
        Computer computer = node.toComputer();
        if (computer == null) {
            result = true;
            LOGGER.log(INFO, "Unable to determine executor status for node {0}", node.getNodeName());
        } else if (computer.isIdle()) {
            result = true;
            LOGGER.log(INFO, "All executors for node {0} are idle", node.getNodeName());
        } else {
            LOGGER.log(INFO, "Node {0} is currently busy", node.getNodeName());
        }
        return result;
    }

    private void removeNode(EC2AbstractSlave ec2Slave) {
        try {
            Jenkins.get().removeNode(ec2Slave);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to remove node: " + ec2Slave.getInstanceId());
        }
    }

}
