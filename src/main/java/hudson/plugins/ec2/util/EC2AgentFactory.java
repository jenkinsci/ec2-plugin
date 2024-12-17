package hudson.plugins.ec2.util;

import hudson.model.Descriptor;
import hudson.plugins.ec2.EC2OndemandSlave;
import hudson.plugins.ec2.EC2SpotSlave;
import java.io.IOException;
import jenkins.model.Jenkins;

public interface EC2AgentFactory {

    static EC2AgentFactory getInstance() {
        EC2AgentFactory instance = null;
        for (EC2AgentFactory implementation : Jenkins.get().getExtensionList(EC2AgentFactory.class)) {
            if (instance != null) {
                throw new IllegalStateException("Multiple implementations of " + EC2AgentFactory.class.getName()
                        + " found. If overriding, please consider using ExtensionFilter");
            }
            instance = implementation;
        }
        return instance;
    }

    EC2OndemandSlave createOnDemandAgent(EC2AgentConfig.OnDemand config) throws Descriptor.FormException, IOException;

    EC2SpotSlave createSpotAgent(EC2AgentConfig.Spot config) throws Descriptor.FormException, IOException;
}
