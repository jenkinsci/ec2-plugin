package hudson.plugins.ec2.util;

import hudson.ExtensionList;
import hudson.model.Descriptor;
import hudson.plugins.ec2.EC2OndemandSlave;
import hudson.plugins.ec2.EC2SpotSlave;
import java.io.IOException;

public interface EC2AgentFactory {

    static EC2AgentFactory getInstance() {
        return ExtensionList.lookupFirst(EC2AgentFactory.class);
    }

    EC2OndemandSlave createOnDemandAgent(EC2AgentConfig.OnDemand config) throws Descriptor.FormException, IOException;

    EC2SpotSlave createSpotAgent(EC2AgentConfig.Spot config) throws Descriptor.FormException, IOException;
}
