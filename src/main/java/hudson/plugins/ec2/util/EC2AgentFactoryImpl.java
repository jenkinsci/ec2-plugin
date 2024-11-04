package hudson.plugins.ec2.util;

import java.io.IOException;
import java.util.logging.Logger;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.plugins.ec2.*;

@Extension
public class EC2AgentFactoryImpl implements EC2AgentFactory {

    private static final Logger LOGGER = Logger.getLogger(EC2SpotSlave.class.getName());

    @Override
    public EC2OndemandSlave createOnDemandAgent(EC2AgentConfig.OnDemand config)
            throws Descriptor.FormException, IOException {
        EC2OndemandSlave instance = new EC2OndemandSlave(config.name, config.instanceId, config.description, config.remoteFS, config.numExecutors, config.labelString, config.mode, config.initScript, config.tmpDir, config.nodeProperties, config.remoteAdmin, config.javaPath, config.jvmopts, config.stopOnTerminate, config.idleTerminationMinutes, config.publicDNS, config.privateDNS, config.tags, config.cloudName, config.launchTimeout, config.amiType, config.connectionStrategy, config.maxTotalUses, config.tenancy, config.metadataEndpointEnabled, config.metadataTokensRequired, config.metadataHopsLimit, config.metadataSupported);
        instance.setInstanceKeypair(config.keyPair);
        LOGGER.fine(() -> "on-demand instance created with keypair " + config.keyPair.getKeyName() + " [" + instance.getInstanceId() + "]");
        LOGGER.fine(() -> "keypair fingerprint is " + config.keyPair.getKeyFingerprint()+ " [" + instance.getInstanceId() + "]");
        LOGGER.fine(() -> "key length is --> " + instance.getInstanceSshPrivateKey().getPlainText().length());
        return instance;
    }

    @Override
    public EC2SpotSlave createSpotAgent(EC2AgentConfig.Spot config) throws Descriptor.FormException, IOException {
        EC2SpotSlave instance =  new EC2SpotSlave(config.name, config.spotInstanceRequestId, config.description, config.remoteFS, config.numExecutors, config.mode, config.initScript, config.tmpDir, config.labelString, config.nodeProperties, config.remoteAdmin, config.javaPath, config.jvmopts, config.idleTerminationMinutes, config.tags, config.cloudName, config.launchTimeout, config.amiType, config.connectionStrategy, config.maxTotalUses);
        instance.setInstanceKeypair(config.keyPair);
        LOGGER.fine(() -> "spot instance created with keypair " + config.keyPair.getKeyName() + " [" + instance.getInstanceId() + "]");
        LOGGER.fine(() -> "keypair fingerprint is " + config.keyPair.getKeyFingerprint()+ " [" + instance.getInstanceId() + "]");
        LOGGER.fine(() -> "key length is --> " + instance.getInstanceSshPrivateKey().getPlainText().length());
        return instance;
    }
}
