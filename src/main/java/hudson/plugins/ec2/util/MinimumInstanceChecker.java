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
     * Checks all EC2 cloud templates and provisions agents to meet minimum instance requirements.
     * Synchronized to prevent concurrent provisioning decisions that could lead to over-provisioning
     * when multiple agents accept tasks simultaneously.
     *
     * @see <a href="https://issues.jenkins.io/browse/JENKINS-76171">JENKINS-76171</a>
     */
    public static synchronized void checkForMinimumInstances() {
        Jenkins jenkins = Jenkins.get();

        // Early exit if no templates have minimum instance requirements
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
                    if (requiredMinSpareAgents > 0) {
                        provisionForMinSpareAgents = (requiredMinSpareAgents + currentBuildsWaitingForTemplate)
                                - (currentNumberOfSpareAgentsForTemplate
                                        + provisionForMinAgents
                                        + currentNumberOfProvisioningAgentsForTemplate);
                        if (provisionForMinSpareAgents < 0) {
                            provisionForMinSpareAgents = 0;
                        }
                    }

                    int numberToProvision = provisionForMinAgents + provisionForMinSpareAgents;

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
