package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.ImageDescription;
import com.xerox.amazonws.ec2.InstanceType;
import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.FormFieldValidator;
import hudson.Extension;
import hudson.slaves.RetentionStrategy;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
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
    public final String initScript;
    protected transient EC2Cloud parent;

    @DataBoundConstructor
    public SlaveTemplate(String ami, String remoteFS, InstanceType type, String label, String description, String initScript) {
        this.ami = ami;
        this.remoteFS = remoteFS;
        this.type = type;
        this.label = label;
        this.description = description;
        this.initScript = initScript;
    }
    
    public EC2Cloud getParent() {
        return parent;
    }

    public String getDisplayName() {
        return description+" ("+ami+")";
    }

    public int getNumExecutors() {
        return EC2Slave.toNumExecutors(type);
    }

    /**
     * Provisions a new EC2 slave.
     *
     * @return always non-null. This needs to be then added to {@link Hudson#addNode(Node)}.
     */
    public EC2Slave provision(TaskListener listener) throws EC2Exception, IOException {
        PrintStream logger = listener.getLogger();
        Jec2 ec2 = getParent().connect();

        try {
            logger.println("Launching "+ami);
            Instance inst = ec2.runInstances(ami, 1, 1, Collections.<String>emptyList(), null, "thekey", type).getInstances().get(0);

            return new EC2Slave(inst.getInstanceId(),description,remoteFS,type,label,initScript);
        } catch (FormException e) {
            throw new AssertionError(); // we should have discovered all configuration issues upfront
        }
    }

    public Descriptor<SlaveTemplate> getDescriptor() {
        return Hudson.getInstance().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {
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
