package hudson.plugins.ec2.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.init.Terminator;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Restricted(NoExternalUse.class)
public class MinimumInstanceChecker {

    private static final Logger LOGGER = Logger.getLogger(MinimumInstanceChecker.class.getName());

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Needs to be overridden from tests")
    public static Clock clock = Clock.systemDefaultZone();

    private static Stream<EC2Computer> agentsForTemplate(@NonNull SlaveTemplate agentTemplate) {
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .filter(computer -> {
                    SlaveTemplate computerTemplate = computer.getSlaveTemplate();
                    return computerTemplate != null
                            && Objects.equals(computerTemplate.description, agentTemplate.description);
                });
    }

    public static int countCurrentNumberOfAgents(@NonNull SlaveTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate).count();
    }

    private static Stream<EC2Computer> idleAgents(@NonNull SlaveTemplate agentTemplate) {
        return agentsForTemplate(agentTemplate).filter(Computer::isIdle);
    }

    public static int countCurrentNumberOfSpareAgents(@NonNull SlaveTemplate agentTemplate) {
        return (int) idleAgents(agentTemplate).filter(Computer::isOnline).count();
    }

    public static int countCurrentNumberOfProvisioningAgents(@NonNull SlaveTemplate agentTemplate) {
        return (int) idleAgents(agentTemplate)
                .filter(Computer::isOffline)
                .filter(Computer::isConnecting)
                .count();
    }

    /*
        Get the number of queued builds that match an AMI (agentTemplate)
    */
    public static int countQueueItemsForAgentTemplate(@NonNull SlaveTemplate agentTemplate) {
        return (int) Queue.getInstance().getBuildableItems().stream()
                .map((Queue.Item item) -> item.getAssignedLabel())
                .filter(Objects::nonNull)
                .filter((Label label) -> label.matches(agentTemplate.getLabelSet()))
                .count();
    }

    /**
     * JENKINS-76171: This method is synchronized to prevent race conditions during concurrent invocations.
     *
     * PROBLEM: This method is called from EC2RetentionStrategy.taskAccepted() when an agent
     * accepts a task. With maxTotalUses=1, multiple agents can accept tasks simultaneously,
     * causing multiple threads to call this method concurrently. Without synchronization,
     * each thread independently calculates capacity and provisions nodes, leading to massive
     * over-provisioning (e.g., 500+ nodes for 100 builds).
     *
     * SOLUTION: The synchronized keyword ensures only one thread executes this method at a time.
     * When thread A provisions nodes, threads B, C, D wait. When thread B enters, it sees the
     * nodes thread A just provisioned and provisions fewer/none, preventing duplicates.
     *
     * PERFORMANCE: Early-exit optimization ensures minimal synchronized block duration when
     * minimumNumberOfInstances and minimumNumberOfSpareInstances are both 0 (recommended).
     */
    public static synchronized void checkForMinimumInstances() {
        Jenkins jenkins = Jenkins.get();

        // JENKINS-76171: Fast early-exit optimization to minimize synchronized block duration
        // Check if ANY template has minimum instance requirements before doing expensive work
        //
        // RECOMMENDED: Set both minimumNumberOfInstances and minimumNumberOfSpareInstances to 0
        // When set to 0, this method exits immediately with minimal overhead, and allows
        // the normal provisioner strategies (like NoDelayProvisionerStrategy) to handle all
        // capacity planning. This provides better scaling behavior and prevents conflicts
        // between MinimumInstanceChecker and other provisioning strategies.
        boolean hasMinimumRequirements = jenkins.clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .flatMap(cloud -> cloud.getTemplates().stream())
                .anyMatch(template ->
                        template.getMinimumNumberOfInstances() > 0 || template.getMinimumNumberOfSpareInstances() > 0);

        if (!hasMinimumRequirements) {
            // No templates require minimum instances - exit immediately
            return;
        }

        jenkins.clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .forEach(cloud -> cloud.getTemplates().forEach(agentTemplate -> {
                    // Minimum instances now have a time range, check to see
                    // if we are within that time range and return early if not.
                    if (!minimumInstancesActive(agentTemplate.getMinimumNumberOfInstancesTimeRangeConfig())) {
                        return;
                    }
                    int requiredMinAgents = agentTemplate.getMinimumNumberOfInstances();
                    int requiredMinSpareAgents = agentTemplate.getMinimumNumberOfSpareInstances();
                    int currentNumberOfAgentsForTemplate = countCurrentNumberOfAgents(agentTemplate);
                    int currentNumberOfSpareAgentsForTemplate = countCurrentNumberOfSpareAgents(agentTemplate);
                    int currentNumberOfProvisioningAgentsForTemplate =
                            countCurrentNumberOfProvisioningAgents(agentTemplate);
                    int currentBuildsWaitingForTemplate = countQueueItemsForAgentTemplate(agentTemplate);
                    int provisionForMinAgents = 0;
                    int provisionForMinSpareAgents = 0;

                    // Check if we need to provision any agents because we
                    // don't have the minimum number of agents
                    provisionForMinAgents = requiredMinAgents - currentNumberOfAgentsForTemplate;
                    if (provisionForMinAgents < 0) {
                        provisionForMinAgents = 0;
                    }

                    // Check if we need to provision any agents because we
                    // don't have the minimum number of spare agents.
                    // Don't double provision if minAgents and minSpareAgents are set.
                    // JENKINS-76171: Only provision for spare capacity if minimumNumberOfSpareInstances > 0
                    // When set to 0, let the normal provisioner strategies handle demand
                    if (requiredMinSpareAgents > 0) {
                        provisionForMinSpareAgents = (requiredMinSpareAgents + currentBuildsWaitingForTemplate)
                                - (currentNumberOfSpareAgentsForTemplate
                                        + provisionForMinAgents
                                        + currentNumberOfProvisioningAgentsForTemplate);
                        if (provisionForMinSpareAgents < 0) {
                            provisionForMinSpareAgents = 0;
                        }
                    } else {
                        provisionForMinSpareAgents = 0;
                    }

                    int numberToProvision = provisionForMinAgents + provisionForMinSpareAgents;

                    // JENKINS-76171 DEBUG: Log minimum instance checker decisions
                    if (numberToProvision > 0 || requiredMinAgents > 0 || requiredMinSpareAgents > 0) {
                        LOGGER.log(
                                Level.FINE,
                                "MinimumInstanceChecker for template {0}: toProvision={1}",
                                new Object[] {agentTemplate.description, numberToProvision});
                    }

                    if (numberToProvision > 0) {
                        cloud.provision(agentTemplate, numberToProvision);
                    }
                }));
    }

    public static boolean minimumInstancesActive(
            MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig) {
        if (minimumNumberOfInstancesTimeRangeConfig == null) {
            return true;
        }
        LocalTime fromTime = minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeFromAsTime();
        LocalTime toTime = minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeToAsTime();

        LocalDateTime now = LocalDateTime.now(clock);
        LocalTime nowTime = LocalTime.from(now); // No date. Easier for comparison on time only.

        boolean passingMidnight = false;
        if (toTime.isBefore(fromTime)) {
            passingMidnight = true;
        }

        if (passingMidnight) {
            if (nowTime.isAfter(fromTime)) {
                String today = now.getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getDay(today);
            } else if (nowTime.isBefore(toTime)) {
                // We've gone past midnight and want to check yesterday's setting.
                String yesterday = now.minusDays(1).getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getDay(yesterday);
            }
        } else {
            if (nowTime.isAfter(fromTime) && nowTime.isBefore(toTime)) {
                String today = now.getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getDay(today);
            }
        }
        return false;
    }

    @Terminator
    public static void discardIdleInstances() throws Exception {
        LOGGER.fine("Looking for idle instances to discard");
        List<Future<?>> futures = new ArrayList<>();
        Jenkins.get().clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .forEach(cloud -> cloud.getTemplates().stream()
                        .filter(SlaveTemplate::getTerminateIdleDuringShutdown)
                        .forEach(agentTemplate -> idleAgents(agentTemplate).forEach(computer -> {
                            EC2AbstractSlave agent = computer.getNode();
                            if (agent != null) {
                                LOGGER.info(() -> "discarding idle instance " + agent.getInstanceId());
                                futures.add(agent.terminate());
                            }
                        })));
        // Must wait; otherwise task could run too late during shutdown, leading to NoClassDefFoundError.
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
    }
}
