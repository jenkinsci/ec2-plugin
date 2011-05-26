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
import hudson.model.TaskListener;
import hudson.model.Label;
import hudson.model.Node;
import hudson.Extension;
import hudson.Util;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Template of {@link EC2Slave} to launch.
 *
 * @author Kohsuke Kawaguchi
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {
    public final String ami;
    public final String description;
    public final String remoteFS;
    public final String sshPort;
    public final InstanceType type;
    public final String labels;
    public final String initScript;
    public final String userData;
    public final String numExecutors;
    public final String remoteAdmin;
    public final String rootCommandPrefix;
    public final String jvmopts;
    protected transient EC2Cloud parent;

    private transient /*almost final*/ Set<LabelAtom> labelSet;

    @DataBoundConstructor
    public SlaveTemplate(String ami, String remoteFS, String sshPort, InstanceType type, String labelString, String description, String initScript, String userData, String numExecutors, String remoteAdmin, String rootCommandPrefix, String jvmopts) {
        this.ami = ami;
        this.remoteFS = remoteFS;
        this.sshPort = sshPort;
        this.type = type;
        this.labels = Util.fixNull(labelString);
        this.description = description;
        this.initScript = initScript;
        this.userData = userData;
        this.numExecutors = Util.fixNull(numExecutors).trim();
        this.remoteAdmin = remoteAdmin;
        this.rootCommandPrefix = rootCommandPrefix;
        this.jvmopts = jvmopts;
        readResolve(); // initialize
    }
    
    public EC2Cloud getParent() {
        return parent;
    }

    public String getLabelString() {
        return labels;
    }

    public String getDisplayName() {
        return description+" ("+ami+")";
    }

    public int getNumExecutors() {
        try {
            return Integer.parseInt(numExecutors);
        } catch (NumberFormatException e) {
            return EC2Slave.toNumExecutors(type);
        }
    }

    public int getSshPort() {
        try {
            return Integer.parseInt(sshPort);
        } catch (NumberFormatException e) {
            return 22;
        }
    }
    public String getRemoteAdmin() {
        return remoteAdmin;
    }

    public String getRootCommandPrefix() {
        return rootCommandPrefix;
    }
    
    public Set getLabelSet(){
    	return labelSet;
    }
    
    /**
     * Does this contain the given label?
     *
     * @param l
     *      can be null to indicate "don't care".
     */
    public boolean containsLabel(Label l) {
        return l==null || labelSet.contains(l);
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
            Instance inst = ec2.runInstances(ami, 1, 1, Collections.<String>emptyList(), userData, keyPair.getKeyName(), type).getInstances().get(0);
            return newSlave(inst);
        } catch (FormException e) {
            throw new AssertionError(); // we should have discovered all configuration issues upfront
        }
    }

    private EC2Slave newSlave(Instance inst) throws FormException, IOException {
        return new EC2Slave(inst.getInstanceId(), description, remoteFS, getSshPort(), getNumExecutors(), labels, initScript, remoteAdmin, rootCommandPrefix, jvmopts);
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
            return newSlave(inst);
        } catch (FormException e) {
            throw new AssertionError(); // we should have discovered all configuration issues upfront
        }
    }

    /**
     * Initializes data structure that we don't persist.
     */
    protected Object readResolve() {
        labelSet = Label.parse(labels);
        return this;
    }

    public Descriptor<SlaveTemplate> getDescriptor() {
        return Hudson.getInstance().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {
        public String getDisplayName() {
            return null;
        }

        /**
         * Since this shares much of the configuration with {@link EC2Computer}, check its help page, too.
         */
        @Override
        public String getHelpFile(String fieldName) {
            String p = super.getHelpFile(fieldName);
            if (p==null)        p = Hudson.getInstance().getDescriptor(EC2Slave.class).getHelpFile(fieldName);
            return p;
        }

        /***
         * Check that the AMI requested is available in the cloud and can be used.
         */
        public FormValidation doValidateAmi(
                @QueryParameter String accessId, @QueryParameter String secretKey,
                @QueryParameter AwsRegion region,
                final @QueryParameter String ami) throws IOException, ServletException {
            Jec2 jec2 = EC2Cloud.connect(accessId, secretKey, region.ec2Endpoint);
            if(jec2!=null) {
                try {
                    List<String> images = new LinkedList<String>();
                    images.add(ami);
                    List<String> owners = new LinkedList<String>();
                    List<String> users = new LinkedList<String>();
                    List<ImageDescription> img = jec2.describeImages(images, owners, users);
                    if(img==null || img.isEmpty())
                        // de-registered AMI causes an empty list to be returned. so be defensive
                        // against other possibilities
                        return FormValidation.error("No such AMI, or not usable with this accessId: "+ami);
                    return FormValidation.ok(img.get(0).getImageLocation()+" by "+img.get(0).getImageOwnerId());
                } catch (EC2Exception e) {
                    return FormValidation.error(e.getMessage());
                }
            } else
                return FormValidation.ok();   // can't test
        }
    }
}
