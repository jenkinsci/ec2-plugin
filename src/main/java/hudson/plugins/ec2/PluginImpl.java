package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Plugin;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Items;
import hudson.util.FormValidation;
import hudson.util.Secret;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Added to handle backwards compatibility of xstream class name mapping.
 */
@Extension
public class PluginImpl extends Plugin implements Describable<PluginImpl> {
    @Override
    public void start() throws Exception {
        // backward compatibility with the legacy class name
        Hudson.XSTREAM.alias("hudson.plugins.ec2.EC2Cloud",AmazonEC2Cloud.class);
        
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
