package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Implementation of {@link NodeProvisioner.Strategy} which will provision a new node immediately as
 * a task enter the queue.
 * Now that EC2 is billed by the minute, we don't really need to wait before provisioning a new node.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension(ordinal = 100)
public class NoDelayProvisionerStrategy extends NodeProvisioner.Strategy {

    private static final Logger LOGGER = Logger.getLogger(NoDelayProvisionerStrategy.class.getName());

    @Override
    public NodeProvisioner.StrategyDecision apply(NodeProvisioner.StrategyState strategyState) {
        final Label label = strategyState.getLabel();

        LoadStatistics.LoadStatisticsSnapshot snapshot = strategyState.getSnapshot();

        int availableCapacity = snapshot.getAvailableExecutors() // live executors (idle)
                + snapshot.getConnectingExecutors() // executors present but not yet connected
                + strategyState
                        .getPlannedCapacitySnapshot() // capacity added by previous strategies from previous rounds
                + strategyState.getAdditionalPlannedCapacity(); // capacity added by previous strategies _this round_
        int currentDemand = snapshot.getQueueLength();

        LOGGER.log(
                Level.FINE, "Available capacity={0}, currentDemand={1}", new Object[] {availableCapacity, currentDemand
                });
        if (availableCapacity < currentDemand) {
            Jenkins jenkinsInstance = Jenkins.get();
            for (Cloud cloud : jenkinsInstance.clouds) {
                if (!(cloud instanceof EC2Cloud ec2)) {
                    continue;
                }
                if (!cloud.canProvision(new Cloud.CloudState(label, 0))) {
                    continue;
                }
                if (!ec2.isNoDelayProvisioning()) {
                    continue;
                }

                int numToProvision = currentDemand - availableCapacity;
                LOGGER.log(Level.FINE, "Planned {0} new nodes", numToProvision);

                Collection<NodeProvisioner.PlannedNode> plannedNodes =
                        cloud.provision(new Cloud.CloudState(label, 0), numToProvision);

                LOGGER.log(Level.FINE, "Planned {0} new nodes", plannedNodes.size());
                strategyState.recordPendingLaunches(plannedNodes);
                availableCapacity += plannedNodes.size();
                LOGGER.log(Level.FINE, "After provisioning, available capacity={0}, currentDemand={1}", new Object[] {
                    availableCapacity, currentDemand
                });
                break;
            }
        }
        if (availableCapacity >= currentDemand) {
            LOGGER.log(Level.FINE, "Provisioning completed");
            return NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
        } else {
            LOGGER.log(Level.FINE, "Provisioning not complete, consulting remaining strategies");
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
    }
}
