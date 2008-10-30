package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.InstanceType;
import com.xerox.amazonws.ec2.ImageDescription;
import com.xerox.amazonws.ec2.EC2Exception;
import hudson.slaves.ComputerLauncher;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormFieldValidator;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.List;

/**
 * Template of {@link EC2Slave} to launch.
 *
 * @author Kohsuke Kawaguchi
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {
    public final String ami;
    public final String description;
    public final String remoteFS;
    public final InstanceType type;
    public final String label;
    public final ComputerLauncher launcher;

    @DataBoundConstructor
    public SlaveTemplate(String ami, String remoteFS, InstanceType type, String label, ComputerLauncher launcher, String description) {
        this.ami = ami;
        this.remoteFS = remoteFS;
        this.type = type;
        this.label = label;
        this.launcher = launcher;
        this.description = description;
    }

    public DescriptorImpl getDescriptor() {
        return DescriptorImpl.INSTANCE;
    }

    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {
        public static final DescriptorImpl INSTANCE = new DescriptorImpl();
        private DescriptorImpl() {
            super(SlaveTemplate.class);
        }

        public String getDisplayName() {
            return null;
        }

        public void doCheckAmi(final @QueryParameter String value) throws IOException, ServletException {
            new FormFieldValidator(null) {
                protected void check() throws IOException, ServletException {
                    EC2Cloud cloud = EC2Cloud.get();
                    if(cloud!=null) {
                        try {
                            List<ImageDescription> img = cloud.connect().describeImages(new String[]{value});
                            ok(img.get(0).getImageLocation()+" by "+img.get(0).getImageOwnerId());
                        } catch (EC2Exception e) {
                            error(e.getMessage());
                        }
                    } else
                        ok();   // can't test
                }
            }.process();
        }
    }
}
