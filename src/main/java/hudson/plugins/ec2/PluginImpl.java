package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Plugin;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Hudson;

/**
 * Added to handle backwards compatibility of xstream class name mapping.
 */
@Extension
public class PluginImpl extends Plugin implements Describable<PluginImpl> {
    @Override
    public void start() throws Exception {
        // backward compatibility with the legacy class name
        Hudson.XSTREAM.alias("hudson.plugins.ec2.EC2Cloud",AmazonEC2Cloud.class);
        // backward compatibility with the legacy instance type
        Hudson.XSTREAM.registerConverter(new InstanceTypeConverter());
        
        load();
    }

    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)Hudson.getInstance().getDescriptorOrDie(getClass());
    }

    public static PluginImpl get() {
        return Hudson.getInstance().getPlugin(PluginImpl.class);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<PluginImpl> {
        @Override
        public String getDisplayName() {
            return "EC2 PluginImpl";
        }
    }
}
