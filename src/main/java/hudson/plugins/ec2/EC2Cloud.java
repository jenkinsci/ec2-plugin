package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.InstanceType;
import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.KeyPairInfo;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormFieldValidator;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hudson's view of EC2. 
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2Cloud extends Cloud {
    private final String accessId;
    private final Secret secretKey;
    private final List<SlaveTemplate> templates;
    private transient KeyPairInfo usableKeyPair;

    @DataBoundConstructor
    public EC2Cloud(String accessId, String secretKey, List<SlaveTemplate> templates) {
        super("ec2");
        this.accessId = accessId.trim();
        this.secretKey = Secret.fromString(secretKey.trim());
        this.templates = templates;
        readResolve(); // set parents
    }

    protected Object readResolve() {
        for (SlaveTemplate t : templates)
            t.parent = this;
        return this;
    }

    public String getAccessId() {
        return accessId;
    }

    public String getSecretKey() {
        return secretKey.getEncryptedValue();
    }

    public List<SlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    public SlaveTemplate getTemplate(String ami) {
        for (SlaveTemplate t : templates)
            if(t.ami.equals(ami))
                return t;
        return null;
    }

    /**
     * Gets or creates a new key such that Hudson knows both the private and
     * the public key. The key can be then used to launch an instance and
     * connect to it.
     */
    public synchronized KeyPairInfo getUsableKeyPair() throws EC2Exception, IOException {
        if(usableKeyPair!=null) return usableKeyPair;

        // check the current key list and find one that we know the private key 
        Jec2 ec2 = connect();
        Set<String> keyNames = new HashSet<String>();
        for( KeyPairInfo kpi : ec2.describeKeyPairs(Collections.<String>emptyList())) {
            keyNames.add(kpi.getKeyName());
            File privateKey = getKeyFileName(kpi);
            if(privateKey.exists()) {
                usableKeyPair = new KeyPairInfo(kpi.getKeyName(),kpi.getKeyFingerprint(),
                    FileUtils.readFileToString(privateKey));
                return usableKeyPair;
            }
        }

        // none available, so create a new key
        for( int i=0; ; i++ ) {
            if(keyNames.contains("hudson-"+i))  continue;

            KeyPairInfo r = ec2.createKeyPair("hudson-"+i);
            FileUtils.writeStringToFile(getKeyFileName(r),r.getKeyMaterial());
            usableKeyPair = r;

            return usableKeyPair;
        }
    }

    private File getKeyFileName(KeyPairInfo kpi) {
        return new File(Hudson.getInstance().getRootDir(),"ec2-"+kpi.getKeyName()+".privateKey");
    }

    public void doProvision(StaplerRequest req, StaplerResponse rsp, @QueryParameter String ami) throws ServletException, IOException {
        checkPermission(PROVISION);
        if(ami==null) {
            sendError("The 'ami' query parameter is missing",req,rsp);
            return;
        }
        SlaveTemplate t = getTemplate(ami);
        if(t==null) {
            sendError("No such AMI: "+ami,req,rsp);
            return;
        }

        StringWriter sw = new StringWriter();
        StreamTaskListener listener = new StreamTaskListener(sw);
        try {
            EC2Slave node = t.provision(listener);
            Hudson.getInstance().addNode(node);

            rsp.sendRedirect2(req.getContextPath()+"/computer/"+node.getNodeName());
        } catch (EC2Exception e) {
            e.printStackTrace(listener.error(e.getMessage()));
            sendError(sw.toString(),req,rsp);
        }
    }

    @Override
    public Collection<PlannedNode> provision(int i) {
        // TODO: when we support labels, we can make more intelligent decisions about which AMI to start
        // for a given provisioning request.
        final SlaveTemplate t = templates.get(0);
        
        List<PlannedNode> r = new ArrayList<PlannedNode>();
        for( ; i>0; i-- ) {
            r.add(new PlannedNode(t.getDisplayName(),
                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                        public Node call() throws Exception {
                            // TODO: record the output somewhere
                            return t.provision(new StreamTaskListener());
                        }
                    })
                    ,t.getNumExecutors()));
        }
        return r;
    }

    public DescriptorImpl getDescriptor() {
        return DescriptorImpl.INSTANCE;
    }

    /**
     * Gets the first {@link EC2Cloud} instance configured in the current Hudson, or null if no such thing exists.
     */
    public static EC2Cloud get() {
        return (EC2Cloud)Hudson.getInstance().clouds.get(DescriptorImpl.INSTANCE);
    }

    /**
     * Connects to EC2 and returns {@link Jec2}, which can then be used to communicate with EC2.
     */
    public Jec2 connect() {
        return new Jec2(accessId,secretKey.toString());
    }

    public static final class DescriptorImpl extends Descriptor<Cloud> {
        public static final DescriptorImpl INSTANCE = new DescriptorImpl();

        private DescriptorImpl() {
            super(EC2Cloud.class);
        }

        public String getDisplayName() {
            return "Amazon EC2";
        }

        public InstanceType[] getInstanceTypes() {
            return InstanceType.values();
        }

        public void doCheckAccessId(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            new FormFieldValidator.Base64(req,rsp,false,false,Messages.EC2Cloud_InvalidAccessId()).process();
        }

        public void doCheckSecretKey(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            new FormFieldValidator.Base64(req,rsp,false,false,Messages.EC2Cloud_InvalidSecretKey()).process();
        }

        public void doTestConnection(StaplerRequest req, StaplerResponse rsp,
                                     @QueryParameter final String accessId, @QueryParameter final String secretKey) throws IOException, ServletException {
            new FormFieldValidator(req,rsp,true) {
                protected void check() throws IOException, ServletException {
                    try {
                        Jec2 jec2 = new Jec2(accessId,Secret.fromString(secretKey).toString());
                        jec2.describeInstances(Collections.<String>emptyList());
                        ok(Messages.EC2Cloud_Success());
                    } catch (EC2Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to check EC2 credential",e);
                        error(e.getMessage());
                    }
                }
            }.process();
        }
    }

    static {
        ALL.add(DescriptorImpl.INSTANCE);
    }

    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());
}
