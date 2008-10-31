package hudson.plugins.ec2;

import hudson.Plugin;
import hudson.slaves.Cloud;
import hudson.slaves.RetentionStrategy;

/**
 * Amazon EC2 plugin entry point.
 * 
 * @author Kohsuke Kawaguchi
 */
public class PluginImpl extends Plugin {
    public void start() throws Exception {
        Cloud.ALL.load(EC2Cloud.class);
        SlaveTemplate.DescriptorImpl.INSTANCE.getDisplayName(); // make sure descriptor is registered
        RetentionStrategy.LIST.add(EC2RetentionStrategy.DescriptorImpl.INSTANCE);
    }
}
