package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.Node;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.plugins.ec2.util.MinimumInstanceChecker;
import jenkins.model.Jenkins;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;

import static hudson.plugins.ec2.EC2Cloud.EC2_REQUEST_EXPIRED_ERROR_CODE;

/**
 * @author Bruno Meneguello
 */
@Extension
public class EC2AgentMonitor extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(EC2AgentMonitor.class.getName());

    private final Long recurrencePeriod;

    public EC2AgentMonitor() {
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
            if (node instanceof EC2AbstractAgent) {
                final EC2AbstractAgent ec2Agent = (EC2AbstractAgent) node;
                try {
                    if (!ec2Agent.isAlive(true)) {
                        LOGGER.info("EC2 instance is dead: " + ec2Agent.getInstanceId());
                        ec2Agent.terminate();
                    }
                } catch (AmazonClientException e) {
                    if (e instanceof AmazonEC2Exception &&
                            EC2_REQUEST_EXPIRED_ERROR_CODE.equals(((AmazonEC2Exception) e).getErrorCode())) {
                        LOGGER.info("EC2 request expired, skipping consideration of " + ec2Agent.getInstanceId() + " due to unknown state.");
                    } else {
                        LOGGER.info("EC2 instance is dead and failed to terminate: " + ec2Agent.getInstanceId());
                        removeNode(ec2Agent);
                    }
                }
            }
        }
    }

    private void removeNode(EC2AbstractAgent ec2Agent) {
        try {
            Jenkins.get().removeNode(ec2Agent);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to remove node: " + ec2Agent.getInstanceId());
        }
    }

}
