package hudson.plugins.ec2;

import com.google.common.annotations.VisibleForTesting;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Implementation of {@link NodeProvisioner.Strategy} which will attempt to restart EC2 nodes
 * that are shut down to meet the demand.
 *
 */
@Extension(ordinal = 101)
public class StartInstanceProvisionerStrategy extends NodeProvisioner.Strategy {

    private static final Logger LOGGER = Logger.getLogger(StartInstanceProvisionerStrategy.class.getName());

    @Override
    public NodeProvisioner.StrategyDecision apply(NodeProvisioner.StrategyState strategyState) {
        final Label label = strategyState.getLabel();

        LOGGER.log(Level.FINEST, "Calling into StartInstanceProvisionerStrategy for label: {0}", label.getExpression());

        LoadStatistics.LoadStatisticsSnapshot snapshot = strategyState.getSnapshot();

        int currentDemand = snapshot.getQueueLength();
        int availableCapacity = getCurrentCapacity(label) +
                strategyState.getAdditionalPlannedCapacity() +
                strategyState.getPlannedCapacitySnapshot();
        LOGGER.log(Level.FINE,"Demand: {0}, Avail Capacity: {1}", new Object[]{currentDemand, availableCapacity});

        if (currentDemand > availableCapacity) {
            Jenkins jenkinsInstance = Jenkins.get();
            LOGGER.log(Level.FINE, "Attempting to find node for label: {0}", label);
            for (Node node : jenkinsInstance.getNodes()) {
                if (nodeHasLabel(node, label.getExpression())) {
                    LOGGER.log(Level.FINE,"Found the node, checking if it's running");
                    if (!isNodeOnline(node)) {
                        LOGGER.log(Level.FINE,"Attempting to start node: {0}", node.getNodeName());
                        PlannedNode plannedNode = startNode(node);
                        if (plannedNode != null) {
                            Collection<NodeProvisioner.PlannedNode> plannedNodes = Collections.singletonList(plannedNode);
                            LOGGER.log(Level.FINE, "Planned {0} new nodes", plannedNodes.size());
                            strategyState.recordPendingLaunches(plannedNodes);
                            availableCapacity += plannedNodes.size();
                            break;
                        }
                    }
                }
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

    @VisibleForTesting
    protected boolean isNodeOnline(Node node) {
        Computer nodeComputer = node.toComputer();
        if (nodeComputer != null) {
            return nodeComputer.isOnline();
        }
        return false;
    }

    private PlannedNode startNode(Node node) {
        Jenkins jenkinsInstance = Jenkins.get();
        PlannedNode plannedNode = null;

        for (Cloud cloud : jenkinsInstance.clouds) {
            if (!(cloud instanceof AmazonEC2Cloud))
                continue;
            AmazonEC2Cloud ec2 = (AmazonEC2Cloud) cloud;
            if (ec2.isStartStopNodes() && ec2.isEc2Node(node)) {
                LOGGER.log(Level.FINE, "Node on {0} of {1} not connected to Jenkins, should be started", new Object[] {ec2.getCloudName(), node.getNodeName()});
                try {
                    plannedNode = ec2.startNode(node);
                } catch (Exception e) {
                    LOGGER.log(Level.INFO, "Unable to start an EC2 Instance for node: " + node.getNodeName(), e);
                }
            }
        }
        return plannedNode;
    }

    private int getCurrentCapacity(Label label) {
        int currentCapacity = 0;
        Jenkins jenkinsInstance = Jenkins.get();
        for (Node node : jenkinsInstance.getNodes()) {
            if (isNodeOnline(node)) {
                Computer computer = node.toComputer();
                if (computer != null && computer.isOnline() && !computer.isConnecting()) {
                    if (nodeHasLabel(node, label.getExpression())) {
                        currentCapacity += node.getNumExecutors();
                    }
                }
            }
        }
        return currentCapacity;
    }

    private boolean nodeHasLabel(Node node, String desiredLabel) {
        for (LabelAtom label : node.getAssignedLabels()) {
            if (label.getExpression().equalsIgnoreCase(desiredLabel)) {
                return true;
            }
        }
        return false;
    }
}
