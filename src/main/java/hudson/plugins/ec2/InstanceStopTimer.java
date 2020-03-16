package hudson.plugins.ec2;

import com.google.common.annotations.VisibleForTesting;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

@Extension
public class InstanceStopTimer extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(InstanceStopTimer.class.getName());

    private static final long STOP_DISABLED = -1;

    public InstanceStopTimer() {
        super(InstanceStopTimer.class.getName());
    }

    protected InstanceStopTimer(String name) {
        super(name);
    }

    @Override protected void execute(TaskListener taskListener) throws IOException, InterruptedException {
        Jenkins jenkinsInstance = Jenkins.get();
        for (Node node : jenkinsInstance.getNodes()) {
            if (shouldStopNode(node)) {
                LOGGER.log(Level.FINEST, "{0} should be stopped", node.getNodeName());
                stopNode(node);
            }
        }
    }

    @Override public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(1);
    }

    private boolean shouldStopNode(Node node) {
        long maxIdleMillis = getMaxIdleMillis();

        if (maxIdleMillis < 0) {
            return false;
        }
        boolean shouldStopNode = false;
        Computer computer = getComputer(node);
        if (computer != null && computer.isOnline() && !computer.isConnecting()) {
            boolean executorWasUsed = false;
            for (Executor executor : computer.getAllExecutors()) {
                if (executor.isIdle()) {
                    long idleStart = executor.getIdleStartMilliseconds();
                    long idleTime = System.currentTimeMillis() - idleStart;
                    LOGGER.log(Level.FINEST, "{0} executor: {1} has been idle for: {2}", new Object[] {node.getNodeName() ,executor.getDisplayName(), idleTime});
                    if (idleTime < maxIdleMillis) {
                        executorWasUsed = true;
                        break;
                    }
                } else {
                    executorWasUsed = true;
                    break;
                }
            }
            shouldStopNode = !executorWasUsed;
        }
        return shouldStopNode;
    }

    private void stopNode(Node node) {
        Jenkins jenkinsInstance = Jenkins.get();

        for (Cloud cloud : jenkinsInstance.clouds) {
            if (!(cloud instanceof AmazonEC2Cloud))
                continue;
            AmazonEC2Cloud ec2 = (AmazonEC2Cloud) cloud;
            if (ec2.isStartStopNodes() && ec2.isEc2Node(node)) {
                LOGGER.log(Level.FINE, "Requesting stop on {0} of {1}", new Object[] {ec2.getCloudName(), node.getNodeName()});
                try {
                    ec2.stopNode(node);
                } catch (Exception e) {
                    LOGGER.log(Level.INFO, "Unable to start an EC2 Instance for node: " + node.getNodeName(), e);
                }
            }
        }
    }

    private long getMaxIdleMillis() {
        long maxMinutes = STOP_DISABLED;
        Jenkins jenkinsInstance = Jenkins.get();
        for (Cloud cloud : jenkinsInstance.clouds) {
            if (!(cloud instanceof AmazonEC2Cloud))
                continue;
            AmazonEC2Cloud ec2 = (AmazonEC2Cloud) cloud;
            if (ec2.isStartStopNodes()) {
                Integer configuredMax = getInteger(ec2.getMaxIdleMinutes());
                if (configuredMax != null) {
                    maxMinutes = Math.max(maxMinutes, configuredMax);
                }
            }
        }
        if (maxMinutes > 0) {
            return TimeUnit.MINUTES.toMillis(maxMinutes);
        }
        return maxMinutes;
    }

    private Integer getInteger(String str) {
        if (str != null) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                LOGGER.log(Level.INFO, "Couldn't get integer from string: {0}", str);
                return null;
            }
        }
        return null;
    }

    @VisibleForTesting
    protected Computer getComputer(Node node) {
        return node.toComputer();
    }
}
