package hudson.plugins.ec2.util;

import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Objects;

@Restricted(NoExternalUse.class)
public class MinimumInstanceChecker {

    public static int countCurrentNumberOfSlaves(@Nonnull SlaveTemplate slaveTemplate) {
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
                        int currentNumberOfSlavesForTemplate = countCurrentNumberOfSlaves(slaveTemplate);
                        int numberToProvision = slaveTemplate.getMinimumNumberOfInstances()
                                - currentNumberOfSlavesForTemplate;
                        if (numberToProvision > 0) {
                            cloud.provision(slaveTemplate, numberToProvision);
                        }
                    }
                });
        });
    }
}
