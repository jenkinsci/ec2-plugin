package hudson.plugins.ec2.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Queue;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

@Restricted(NoExternalUse.class)
public class MinimumInstanceChecker {

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Needs to be overridden from tests")
    public static Clock clock = Clock.systemDefaultZone();

    private static Stream<Computer> agentsForTemplate(@Nonnull SlaveTemplate agentTemplate) {
        return (Stream<Computer>) Arrays.stream(Jenkins.get().getComputers())
                .filter(computer -> computer instanceof EC2Computer)
                .filter(computer -> {
                    SlaveTemplate computerTemplate = ((EC2Computer) computer).getSlaveTemplate();
                    return computerTemplate != null
                            && Objects.equals(computerTemplate.description, agentTemplate.description);
                });
    }

    public static int countCurrentNumberOfAgents(@Nonnull SlaveTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate).count();
    }

    public static int countCurrentNumberOfSpareAgents(@Nonnull SlaveTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate)
            .filter(computer -> computer.countBusy() == 0)
            .filter(computer -> computer.isOnline())
            .count();
    }

    public static int countCurrentNumberOfProvisioningAgents(@Nonnull SlaveTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate)
            .filter(computer -> computer.countBusy() == 0)
            .filter(computer -> computer.isOffline())
            .filter(computer -> computer.isConnecting())
            .count();
    }

    /*
        Get the number of queued builds that match an AMI (agentTemplate)
    */
    public static int countQueueItemsForAgentTemplate(@Nonnull SlaveTemplate agentTemplate) {
        return (int)
            Queue
            .getInstance()
            .getBuildableItems()
            .stream()
            .map((Queue.Item item) -> item.getAssignedLabel())
            .filter(Objects::nonNull)
            .filter((Label label) -> label.matches(agentTemplate.getLabelSet()))
            .count();
    }

    public static void checkForMinimumInstances() {
        Jenkins.get().clouds.stream()
            .filter(cloud -> cloud instanceof EC2Cloud)
            .map(cloud -> (EC2Cloud) cloud)
            .forEach(cloud -> {
                cloud.getTemplates().forEach(agentTemplate -> {
                    // Minimum instances now have a time range, check to see
                    // if we are within that time range and return early if not.
                    if (! minimumInstancesActive(agentTemplate.getMinimumNumberOfInstancesTimeRangeConfig())) {
                        return;
                    }
                    int requiredMinAgents = agentTemplate.getMinimumNumberOfInstances();
                    int requiredMinSpareAgents = agentTemplate.getMinimumNumberOfSpareInstances();
                    int currentNumberOfAgentsForTemplate = countCurrentNumberOfAgents(agentTemplate);
                    int currentNumberOfSpareAgentsForTemplate = countCurrentNumberOfSpareAgents(agentTemplate);
                    int currentNumberOfProvisioningAgentsForTemplate = countCurrentNumberOfProvisioningAgents(agentTemplate);
                    int currentBuildsWaitingForTemplate = countQueueItemsForAgentTemplate(agentTemplate);
                    int provisionForMinAgents = 0;
                    int provisionForMinSpareAgents = 0;

                    // Check if we need to provision any agents because we
                    // don't have the minimum number of agents
                    provisionForMinAgents = requiredMinAgents - currentNumberOfAgentsForTemplate;
                    if (provisionForMinAgents < 0){
                        provisionForMinAgents = 0;
                    }

                    // Check if we need to provision any agents because we
                    // don't have the minimum number of spare agents.
                    // Don't double provision if minAgents and minSpareAgents are set.
                    provisionForMinSpareAgents = (requiredMinSpareAgents + currentBuildsWaitingForTemplate) -
                                                 (
                                                    currentNumberOfSpareAgentsForTemplate +
                                                    provisionForMinAgents +
                                                    currentNumberOfProvisioningAgentsForTemplate
                                                  );
                    if (provisionForMinSpareAgents < 0){
                        provisionForMinSpareAgents = 0;
                    }

                    int numberToProvision = provisionForMinAgents + provisionForMinSpareAgents;
                    if (numberToProvision > 0) {
                        cloud.provision(agentTemplate, numberToProvision);
                    }
                });
        });
    }

    public static boolean minimumInstancesActive(
        MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig) {
        if (minimumNumberOfInstancesTimeRangeConfig == null || inimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeDays() == null) {
            return true;
        }
        LocalTime fromTime = minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeFromAsTime();
        LocalTime toTime = minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeToAsTime();

        LocalDateTime now = LocalDateTime.now(clock);
        LocalTime nowTime = LocalTime.from(now); //No date. Easier for comparison on time only.

        boolean passingMidnight = false;
        if (toTime.isBefore(fromTime)) {
            passingMidnight = true;
        }

        if (passingMidnight) {
            if (nowTime.isAfter(fromTime)) {
                String today = now.getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeDays().get(today);
            } else if (nowTime.isBefore(toTime)) {
                //We've gone past midnight and want to check yesterday's setting.
                String yesterday = now.minusDays(1).getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeDays().get(yesterday);
            }
        } else {
            if (nowTime.isAfter(fromTime) && nowTime.isBefore(toTime)) {
                String today = now.getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeDays().get(today);
            }
        }
        return false;
    }
}
