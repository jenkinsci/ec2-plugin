package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.InstanceType;
import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.KeyPairInfo;
import com.xerox.amazonws.ec2.ReservationDescription;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import org.jets3t.service.Constants;
import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.utils.ServiceUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import org.apache.commons.httpclient.HostConfiguration;
import org.jets3t.service.Jets3tProperties;
import static java.util.logging.Level.WARNING;

/**
 * Hudson's view of EC2. 
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2Cloud extends Cloud {
    private final String accessId;
    private final Secret secretKey;
    private final EC2PrivateKey privateKey;
    private final String ec2HostName; // host to send EC2 requests to
    private final Integer ec2Port; // port number to send EC2 requests to (-1 for auto)
    private final String ec2UrlBase; // if API requests are not rooted at /
    private final String s3HostName; // host to send S3 requests to
    private final Integer s3Port; // port number to send S3 requests to (-1 for auto)
    private final String s3UrlBase; // if API requests are not rooted at /
    private final boolean SSL; // use ssl to talk to API's.

    /**
     * Upper bound on how many instances we may provision.
     */
    public final int instanceCap;
    private final List<SlaveTemplate> templates;
    private transient KeyPairInfo usableKeyPair;

    @DataBoundConstructor
    public EC2Cloud(String accessId, String secretKey, String privateKey,
            String ec2HostName, String ec2UrlBase, String ec2Port,
            String s3HostName, String s3UrlBase, String s3Port,
            String instanceCapStr, boolean SSL, List<SlaveTemplate> templates) {
        super("ec2");
        this.accessId = accessId.trim();
        this.secretKey = Secret.fromString(secretKey.trim());
        this.privateKey = new EC2PrivateKey(privateKey);
        // converted just-in-time so we don't save aliases to the config file. see convert*HostName
	this.ec2HostName = ec2HostName;
        this.s3HostName = s3HostName;
        this.ec2UrlBase = convertUrlBase(ec2UrlBase);
        this.s3UrlBase = convertUrlBase(s3UrlBase);
        this.ec2Port = convertPort(ec2Port);
        this.s3Port = convertPort(s3Port);
        this.SSL = SSL;
        if(instanceCapStr.equals(""))
            this.instanceCap = Integer.MAX_VALUE;
        else
            this.instanceCap = Integer.parseInt(instanceCapStr);
        if(templates==null)     templates=Collections.emptyList();
        this.templates = templates;
        readResolve(); // set parents
    }

    private String convertUrlBase(String ec2UrlBase) {
        if (ec2UrlBase == null || ec2UrlBase.length() == 0)
            return "/";
        else
            return ec2UrlBase;
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

    public EC2PrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getec2HostName() {
        return ec2HostName;
    }

    public Integer getec2Port() {
        return ec2Port;
    }

    public String getec2UrlBase() {
        return ec2UrlBase;
    }

    public String gets3HostName() {
        return s3HostName;
    }

    public Integer gets3Port() {
        return s3Port;
    }

    public String gets3UrlBase() {
        return s3UrlBase;
    }

    public boolean getSSL() {
        return SSL;
    }

    public String getInstanceCapStr() {
        if(instanceCap==Integer.MAX_VALUE)
            return "";
        else
            return String.valueOf(instanceCap);
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
     * Gets {@link SlaveTemplate} that has the matching {@link Label}.
     */
    public SlaveTemplate getTemplate(Label label) {
        for (SlaveTemplate t : templates)
            if(t.containsLabel(label))
                return t;
        return null;
    }

    /**
     * Gets the {@link KeyPairInfo} used for the launch.
     */
    public synchronized KeyPairInfo getKeyPair() throws EC2Exception, IOException {
        if(usableKeyPair==null)
            usableKeyPair = privateKey.find(connect());
        return usableKeyPair;
    }

    /**
     * Counts the number of instances in EC2 currently running.
     *
     * <p>
     * This includes those instances that may be started outside Hudson.
     */
    public int countCurrentEC2Slaves() throws EC2Exception {
        int n=0;
        for (ReservationDescription r : connect().describeInstances(Collections.<String>emptyList())) {
            for (Instance i : r.getInstances()) {
                if(!i.isTerminated())
                    n++;
            }
        }
        return n;
    }

    /**
     * Debug command to attach to a running instance.
     */
    public void doAttach(StaplerRequest req, StaplerResponse rsp, @QueryParameter String id) throws ServletException, IOException, EC2Exception {
        checkPermission(PROVISION);
        SlaveTemplate t = getTemplates().get(0);

        StringWriter sw = new StringWriter();
        StreamTaskListener listener = new StreamTaskListener(sw);
        EC2Slave node = t.attach(id,listener);
        Hudson.getInstance().addNode(node);

        rsp.sendRedirect2(req.getContextPath()+"/computer/"+node.getNodeName());
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

    public Collection<PlannedNode> provision(Label label, int excessWorkload) {
        try {

            final SlaveTemplate t = getTemplate(label);

            List<PlannedNode> r = new ArrayList<PlannedNode>();
            for( ; excessWorkload>0; excessWorkload-- ) {
                if(countCurrentEC2Slaves()>=instanceCap)
                    break;      // maxed out

                r.add(new PlannedNode(t.getDisplayName(),
                        Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                            public Node call() throws Exception {
                                // TODO: record the output somewhere
                                EC2Slave s = t.provision(new StreamTaskListener(System.out));
                                Hudson.getInstance().addNode(s);
                                // EC2 instances may have a long init script. If we declare
                                // the provisioning complete by returning without the connect
                                // operation, NodeProvisioner may decide that it still wants
                                // one more instance, because it sees that (1) all the slaves
                                // are offline (because it's still being launched) and
                                // (2) there's no capacity provisioned yet.
                                //
                                // deferring the completion of provisioning until the launch
                                // goes successful prevents this problem.
                                s.toComputer().connect(false).get();
                                return s;
                            }
                        })
                        ,t.getNumExecutors()));
            }
            return r;
        } catch (EC2Exception e) {
            LOGGER.log(WARNING,"Failed to count the # of live instances on EC2",e);
            return Collections.emptyList();
        }
    }

    public boolean canProvision(Label label) {
        return getTemplate(label)!=null;
    }

    /**
     * Gets the first {@link EC2Cloud} instance configured in the current Hudson, or null if no such thing exists.
     */
    public static EC2Cloud get() {
        return Hudson.getInstance().clouds.get(EC2Cloud.class);
    }

    /**
     * Connects to EC2 and returns {@link Jec2}, which can then be used to communicate with EC2.
     */
    public Jec2 connect() {
        /* TODO: Permit port selection as well */
        return connect(accessId, secretKey, ec2HostName, ec2Port, ec2UrlBase, SSL);
    }

    /***
     * Connect to an EC2 instance.
     * @return Jec2
     */
    public static Jec2 connect(String accessId, String secretKey,
            String ec2HostName, Integer ec2Port, String ec2UrlBase, boolean SSL) {
        return connect(accessId, Secret.fromString(secretKey), ec2HostName,
                ec2Port, ec2UrlBase, SSL);
    }

    /***
     * Connect to an EC2 instance.
     * @return Jec2
     */
    public static Jec2 connect(String accessId, Secret secretKey,
            String ec2HostName, Integer ec2Port, String ec2UrlBase, boolean SSL) {
        if (ec2Port == -1)
            ec2Port = SSL ? 443 : 80;
        Jec2 result = new Jec2(accessId, secretKey.toString(), SSL, convertHostName(ec2HostName), ec2Port);
        result.setResourcePrefix(ec2UrlBase);
        return result;
    }

    /***
     * Convert a configured hostname like 'us-east-1' to a FQDN or ip address
     */
    public static String convertHostName(String ec2HostName) {
        if (ec2HostName == null || ec2HostName.length()==0)
            ec2HostName = "us-east-1";
        if (!ec2HostName.contains("."))
            ec2HostName = ec2HostName + ".ec2.amazonaws.com";
	return ec2HostName;
    }

    /***
     * Convert a configured s3 endpoint to a FQDN or ip address
     */
    public static String convertS3HostName(String s3HostName) {
        if (s3HostName == null || s3HostName.length()==0)
            s3HostName = "s3";
        if (!s3HostName.contains("."))
            s3HostName = s3HostName + ".amazonaws.com";
	return s3HostName;
    }

    /***
     * Convert a user entered string into a port number
     * "" -> -1 to indicate default based on SSL setting
     */
    public static Integer convertPort(String ec2Port) {
        if (ec2Port == null || ec2Port.length() == 0)
            return -1;
        else
            return Integer.parseInt(ec2Port);
    }

    /**
     * Connects to S3 and returns {@link S3Service}.
     */
    public S3Service connectS3() throws S3ServiceException {
        Jets3tProperties props = Jets3tProperties.getInstance(Constants.JETS3T_PROPERTIES_FILENAME);
        final String s3Host = convertS3HostName(s3HostName);
        if (!s3Host.equals("s3.amazonaws.com"))
            props.setProperty("s3service.s3-endpoint", s3HostName);
        if (s3Port != -1)
            props.setProperty("s3service.s3-endpoint-http-port", s3Port.toString());
        if (!s3UrlBase.equals("/"))
            props.setProperty("s3service.s3-endpoint-virtual-path", s3UrlBase);
        props.setProperty("s3service.https-only", new Boolean(SSL).toString());
        if (!s3Host.endsWith(".amazonaws.com"))
            /* For eucalyptus as of 1.6.0 */
            props.setProperty("s3service.disable-dns-buckets", "true");
        return new RestS3Service(new AWSCredentials(accessId,secretKey.toString()),
            null, null, props);
    }

    /**
     * Computes the presigned URL for the given S3 resource.
     *
     * @param path
     *      String like "/bucketName/folder/folder/abc.txt" that represents the resource to request.
     */
    public URL buildPresignedURL(String path) throws IOException, S3ServiceException {
        long expires = System.currentTimeMillis()/1000+60*60;
        String token = "GET\n\n\n" + expires + "\n" + path;

        String url = "http://s3.amazonaws.com"+path+"?AWSAccessKeyId="+accessId+"&Expires="+expires+"&Signature="+
                URLEncoder.encode(
                        ServiceUtils.signWithHmacSha1(secretKey.toString(),token),"UTF-8");
        return new URL(url);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {
        public String getDisplayName() {
            return "Amazon EC2";
        }

        public InstanceType[] getInstanceTypes() {
            return InstanceType.values();
        }

        /**
         * TODO: once 1.304 is released, revert to FormValidation.validateBase64
         */
        private FormValidation validateBase64(String value, boolean allowWhitespace, boolean allowEmpty, String errorMessage) {
            try {
                String v = value;
                if(!allowWhitespace) {
                    if(v.indexOf(' ')>=0 || v.indexOf('\n')>=0)
                        return FormValidation.error(errorMessage);
                }
                v=v.trim();
                if(!allowEmpty && v.length()==0)
                    return FormValidation.error(errorMessage);

                com.trilead.ssh2.crypto.Base64.decode(v.toCharArray());
                return FormValidation.ok();
            } catch (IOException e) {
                return FormValidation.error(errorMessage);
            }
        }

        public FormValidation doCheckAccessId(@QueryParameter String value) throws IOException, ServletException {
            return validateBase64(value,false,false,Messages.EC2Cloud_InvalidAccessId());
        }

        public FormValidation doCheckSecretKey(@QueryParameter String value) throws IOException, ServletException {
            return validateBase64(value,false,false,Messages.EC2Cloud_InvalidSecretKey());
        }

        public FormValidation doCheckPrivateKey(@QueryParameter String value) throws IOException, ServletException {
            boolean hasStart=false,hasEnd=false;
            BufferedReader br = new BufferedReader(new StringReader(value));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                    hasStart=true;
                if (line.equals("-----END RSA PRIVATE KEY-----"))
                    hasEnd=true;
            }
            if(!hasStart)
                return FormValidation.error("This doesn't look like a private key at all");
            if(!hasEnd)
                return FormValidation.error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            return FormValidation.ok();
        }

        public FormValidation doTestConnection(
                                     @QueryParameter String accessId, @QueryParameter String secretKey,
                                     @QueryParameter String privateKey,
                                     @QueryParameter String ec2HostName,
                                     @QueryParameter String ec2Port,
                                     @QueryParameter String ec2UrlBase,
                                     @QueryParameter boolean SSL) throws IOException, ServletException {
            try {
                Jec2 jec2 = connect(accessId, secretKey, ec2HostName, convertPort(ec2Port), ec2UrlBase, SSL);
                jec2.describeInstances(Collections.<String>emptyList());

                if(accessId==null)
                    return FormValidation.error("Access ID is not specified");
                if(secretKey==null)
                    return FormValidation.error("Secret key is not specified");
                if(privateKey==null)
                    return FormValidation.error("Private key is not specified. Click 'Generate Key' to generate one.");

                if(privateKey.trim().length()>0) {
                    // check if this key exists
                    EC2PrivateKey pk = new EC2PrivateKey(privateKey);
                    if(pk.find(jec2)==null)
                        return FormValidation.error("The private key entered below isn't registred to EC2 (fingerprint is "+pk.getFingerprint()+")");
                }

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (EC2Exception e) {
                LOGGER.log(WARNING, "Failed to check EC2 credential",e);
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doGenerateKey(StaplerResponse rsp,
                                     @QueryParameter String accessId,
                                     @QueryParameter String secretKey,
                                     @QueryParameter String ec2HostName,
                                     @QueryParameter String ec2Port,
                                     @QueryParameter String ec2UrlBase,
                                     @QueryParameter boolean SSL) throws IOException, ServletException {
            try {
                Jec2 jec2 = connect(accessId, secretKey, ec2HostName, convertPort(ec2Port), ec2UrlBase, SSL);
                List<KeyPairInfo> existingKeys = jec2.describeKeyPairs(Collections.<String>emptyList());

                int n = 0;
                while(true) {
                    boolean found = false;
                    for (KeyPairInfo k : existingKeys) {
                        if(k.getKeyName().equals("hudson-"+n))
                            found=true;
                    }
                    if(!found)
                        break;
                    n++;
                }

                KeyPairInfo key = jec2.createKeyPair("hudson-" + n);


                rsp.addHeader("script","findPreviousFormItem(button,'privateKey').value='"+key.getKeyMaterial().replace("\n","\\n")+"'");

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (EC2Exception e) {
                LOGGER.log(WARNING, "Failed to check EC2 credential",e);
                return FormValidation.error(e.getMessage());
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());
}
