package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.ImageDescription;
import com.xerox.amazonws.ec2.InstanceType;
import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.KeyPairInfo;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.Label;
import hudson.util.FormFieldValidator;
import hudson.Extension;
import hudson.Util;
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
    public final String labels;
    public final String initScript;
    protected transient EC2Cloud parent;

    @DataBoundConstructor
    public SlaveTemplate(String ami, String remoteFS, InstanceType type, String labels, String description, String initScript) {
        this.ami = ami;
        this.remoteFS = remoteFS;
        this.type = type;
        this.labels = Util.fixNull(labels);
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
     * Does this contain the given label?
     *
     * @param l
     *      can be null to indicate "don't care".
     */
    public boolean containsLabel(Label l) {
        if(l==null) return true;

        for(String t : labels.split("\\s+"))
            if(t.equals(l.getName()))
                return true;
        return false;
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
            KeyPairInfo keyPair = parent.getPrivateKey().find(ec2);
            if(keyPair==null)
                throw new EC2Exception("No matching keypair found on EC2. Is the EC2 private key a valid one?");
            Instance inst = ec2.runInstances(ami, 1, 1, Collections.<String>emptyList(), null, keyPair.getKeyName(), type).getInstances().get(0);

            return new EC2Slave(inst.getInstanceId(),description,remoteFS,type, labels,initScript);
        } catch (FormException e) {
            throw new AssertionError(); // we should have discovered all configuration issues upfront
        }
    }

    /**
     * Provisions a new EC2 slave based on the currently running instance on EC2,
     * instead of starting a new one.
     */
    public EC2Slave attach(String instanceId, TaskListener listener) throws EC2Exception, IOException {
        PrintStream logger = listener.getLogger();
        Jec2 ec2 = getParent().connect();

        try {
            logger.println("Attaching to "+instanceId);
            Instance inst = ec2.describeInstances(Collections.singletonList(instanceId)).get(0).getInstances().get(0);

            return new EC2Slave(inst.getInstanceId(),description,remoteFS,type, labels,initScript);
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
