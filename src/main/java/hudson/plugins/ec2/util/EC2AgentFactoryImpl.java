package hudson.plugins.ec2.util;

import java.io.IOException;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.plugins.ec2.*;

@Extension
public class EC2AgentFactoryImpl implements EC2AgentFactory {

    @Override
    public EC2OndemandSlave createOnDemandAgent(EC2AgentConfig.OnDemand config)
            throws Descriptor.FormException, IOException {
        EC2OndemandSlave instance = new EC2OndemandSlave(config.name, config.instanceId, config.description, config.remoteFS, config.numExecutors, config.labelString, config.mode, config.initScript, config.tmpDir, config.nodeProperties, config.remoteAdmin, config.javaPath, config.jvmopts, config.stopOnTerminate, config.idleTerminationMinutes, config.publicDNS, config.privateDNS, config.tags, config.cloudName, config.launchTimeout, config.amiType, config.connectionStrategy, config.maxTotalUses, config.tenancy, config.metadataEndpointEnabled, config.metadataTokensRequired, config.metadataHopsLimit, config.metadataSupported);
        instance.setInstanceKeypair(config.keyPair);
        return instance;
    }

    @Override
    public EC2SpotSlave createSpotAgent(EC2AgentConfig.Spot config) throws Descriptor.FormException, IOException {
        EC2SpotSlave instance =  new EC2SpotSlave(config.name, config.spotInstanceRequestId, config.description, config.remoteFS, config.numExecutors, config.mode, config.initScript, config.tmpDir, config.labelString, config.nodeProperties, config.remoteAdmin, config.javaPath, config.jvmopts, config.idleTerminationMinutes, config.tags, config.cloudName, config.launchTimeout, config.amiType, config.connectionStrategy, config.maxTotalUses, config.keyPair);
        instance.setInstanceKeypair(config.keyPair);
        return instance;
    }
}
