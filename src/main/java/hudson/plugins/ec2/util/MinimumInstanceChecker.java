package hudson.plugins.ec2.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Objects;

@Restricted(NoExternalUse.class)
public class MinimumInstanceChecker {

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Needs to be overridden from tests")
    public static Clock clock = Clock.systemDefaultZone();

    public static int countCurrentNumberOfAgents(@Nonnull SlaveTemplate slaveTemplate) {
        return (int) Arrays.stream(Jenkins.get().getComputers()).filter(computer -> {
            if (computer instanceof EC2Computer) {
                SlaveTemplate computerTemplate = ((EC2Computer) computer).getSlaveTemplate();
                if (computerTemplate != null) {
                    return Objects.equals(computerTemplate.description, slaveTemplate.description);
                }
            }
            return false;
        }).count();
    }

    public static void checkForMinimumInstances() {
        Jenkins.get().clouds.stream()
            .filter(cloud -> cloud instanceof EC2Cloud)
            .map(cloud -> (EC2Cloud) cloud)
            .forEach(cloud -> {
                cloud.getTemplates().forEach(slaveTemplate -> {
                    if (slaveTemplate.getMinimumNumberOfInstances() > 0) {
                        if (minimumInstancesActive(slaveTemplate.getMinimumNumberOfInstancesTimeRangeConfig())) {
                            int currentNumberOfSlavesForTemplate = countCurrentNumberOfAgents(slaveTemplate);
                            int numberToProvision = slaveTemplate.getMinimumNumberOfInstances()
                                - currentNumberOfSlavesForTemplate;
                            if (numberToProvision > 0) {
                                cloud.provision(slaveTemplate, numberToProvision);
                            }
                        }
                    }
                });
        });
    }

    public static boolean minimumInstancesActive(
        MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig) {
        if (minimumNumberOfInstancesTimeRangeConfig == null) {
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
