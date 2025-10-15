package hudson.plugins.ec2;

import com.google.common.annotations.VisibleForTesting;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Node;
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

        // JENKINS-76171: Count provisioned EC2 nodes that exist but haven't started executing jobs yet.
        // This prevents over-provisioning by accounting for nodes in the gap between:
        // 1) PlannedNode future completing (instance RUNNING)
        // 2) Agent showing as "connecting" in the snapshot
        // 3) Agent executing jobs
        int provisionedButNotExecuting = countProvisionedButNotExecutingNodes(label);

        int availableCapacity = snapshot.getAvailableExecutors() // live executors (idle)
                + snapshot.getConnectingExecutors() // executors present but not yet connected
                + strategyState
                        .getPlannedCapacitySnapshot() // capacity added by previous strategies from previous rounds
                + strategyState.getAdditionalPlannedCapacity() // capacity added by previous strategies _this round_
                + provisionedButNotExecuting; // EC2 nodes that exist but aren't yet counted above
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

    /**
     * Counts executors in EC2 nodes that have been provisioned (exist in Jenkins) but are NOT yet counted in the
     * LoadStatistics snapshot. This specifically targets the gap where nodes exist but are:
     * - Offline (just added to Jenkins, before connecting starts)
     * - Instance is PENDING or RUNNING in AWS (will come online soon)
     *
     * We explicitly DO NOT count:
     * - Connecting nodes (already in snapshot.getConnectingExecutors())
     * - Online nodes (already in snapshot.getAvailableExecutors() or busy executors)
     * - STOPPED instances (won't come online without explicit start action)
     *
     * This prevents over-provisioning by accounting for nodes in the critical gap between:
     * 1) Node added to Jenkins (after PlannedNode future completes)
     * 2) Node starts connecting (shows up in snapshot.getConnectingExecutor())
     *
     * JENKINS-76200: Exclude STOPPED instances - they won't come online on their own.
     *
     * @param label the label to match, or null for unlabeled nodes
     * @return the number of executors from provisioned EC2 nodes in the offline->connecting gap
     */
    @VisibleForTesting
    int countProvisionedButNotExecutingNodes(Label label) {
        Jenkins jenkins = Jenkins.get();
        // Use Label.getNodes() to leverage core's label matching and caching
        java.util.Set<Node> nodes = (label != null) ? label.getNodes() : java.util.Set.copyOf(jenkins.getNodes());

        int count = 0;
        int totalEC2Nodes = 0;
        int offlineNodes = 0;
        int connectingNodes = 0;
        int onlineNodes = 0;
        int stoppedNodes = 0;

        for (Node node : nodes) {
            // Only count EC2 nodes
            if (!(node instanceof EC2AbstractSlave)) {
                continue;
            }
            totalEC2Nodes++;

            Computer computer = node.toComputer();
            if (computer == null) {
                continue;
            }

            // Track node states for debugging
            if (computer.isOnline()) {
                onlineNodes++;
            } else if (computer.isConnecting()) {
                connectingNodes++;
            } else if (computer.isOffline()) {
                offlineNodes++;
            }

            // Only count nodes that are OFFLINE (not connecting, not online)
            // and not STOPPED in AWS (won't come online without explicit start)
            if (computer.isOffline() && !computer.isConnecting()) {
                // JENKINS-76200: Check if instance is STOPPED in AWS
                if (computer instanceof EC2Computer ec2Computer) {
                    try {
                        InstanceState state = ec2Computer.getState();
                        if (state == InstanceState.STOPPED || state == InstanceState.STOPPING) {
                            stoppedNodes++;
                            LOGGER.log(
                                    Level.FINE,
                                    "Excluding STOPPED instance {0} from available capacity",
                                    ec2Computer.getInstanceId());
                            continue; // Don't count stopped instances
                        }
                    } catch (Exception e) {
                        LOGGER.log(
                                Level.FINE,
                                "Could not get state for " + ec2Computer.getName() + ", counting as available",
                                e);
                        // If we can't determine state, count it to avoid over-provisioning
                    }
                }
                count += node.getNumExecutors();
            }
        }

        LOGGER.log(
                Level.FINER,
                "EC2 nodes for label {0}: total={1}, offline={2}, connecting={3}, online={4}, stopped={5}",
                new Object[] {label, totalEC2Nodes, offlineNodes, connectingNodes, onlineNodes, stoppedNodes});

        return count;
    }
}
