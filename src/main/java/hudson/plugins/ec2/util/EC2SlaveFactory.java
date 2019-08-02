package hudson.plugins.ec2.util;

import java.io.IOException;

import hudson.model.Descriptor;
import hudson.plugins.ec2.*;
import jenkins.model.Jenkins;

public interface EC2SlaveFactory {

    static EC2SlaveFactory getInstance() {
        EC2SlaveFactory instance = null;
        for (EC2SlaveFactory implementation : Jenkins.get().getExtensionList(EC2SlaveFactory.class)) {
            if (instance != null) {
                throw new IllegalStateException("Multiple implementations of " + EC2SlaveFactory.class.getName()
                        + " found. If overriding, please consider using ExtensionFilter");
            }
            instance = implementation;
        }
        return instance;
    }

    EC2OndemandSlave createOnDemandSlave(EC2SlaveConfig.OnDemand config) throws Descriptor.FormException, IOException;

    EC2SpotSlave createSpotSlave(EC2SlaveConfig.Spot config) throws Descriptor.FormException, IOException;

}
