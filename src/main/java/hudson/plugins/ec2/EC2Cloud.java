package hudson.plugins.ec2;

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;


/**
 * Hudson's view of EC2. 
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2Cloud extends Cloud {

	public static final String DEFAULT_EC2_HOST = "us-east-1";
	public static final String EC2_URL_HOST = "ec2.amazonaws.com";
	
    private final String accessId;
    private final Secret secretKey;
    private final EC2PrivateKey privateKey;

    /**
     * Upper bound on how many instances we may provision.
     */
    public final int instanceCap;
    private final List<SlaveTemplate> templates;
    private transient KeyPair usableKeyPair;

    private transient AmazonEC2 connection;
    
	private static AWSCredentials awsCredentials;
    
    protected EC2Cloud(String id, String accessId, String secretKey, String privateKey, String instanceCapStr, List<SlaveTemplate> templates) {
        super(id);
        this.accessId = accessId.trim();
        this.secretKey = Secret.fromString(secretKey.trim());
        this.privateKey = new EC2PrivateKey(privateKey);
        if(instanceCapStr.equals(""))
            this.instanceCap = Integer.MAX_VALUE;
        else
            this.instanceCap = Integer.parseInt(instanceCapStr);
        if(templates==null)     templates=Collections.emptyList();
        this.templates = templates;
        readResolve(); // set parents
    }

    public abstract URL getEc2EndpointUrl() throws IOException;
    public abstract URL getS3EndpointUrl() throws IOException;

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
        	if(label == null || label.matches(t.getLabelSet()))
                return t;
        return null;
    }

    /**
     * Gets the {@link KeyPairInfo} used for the launch.
     */
    public synchronized KeyPair getKeyPair() throws AmazonClientException, IOException {
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
    public int countCurrentEC2Slaves() throws AmazonClientException {
        int n=0;
        for (Reservation r : connect().describeInstances().getReservations()) {
            for (Instance i : r.getInstances()) {
                InstanceStateName stateName = InstanceStateName.fromValue(i.getState().getName());
                if (stateName == InstanceStateName.Pending || stateName == InstanceStateName.Running)
                    n++;
            }
        }
        return n;
    }

    /**
     * Debug command to attach to a running instance.
     */
    public void doAttach(StaplerRequest req, StaplerResponse rsp, @QueryParameter String id) throws ServletException, IOException, AmazonClientException {
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
        } catch (AmazonClientException e) {
            e.printStackTrace(listener.error(e.getMessage()));
            sendError(sw.toString(),req,rsp);
        }
    }

    @Override
	public Collection<PlannedNode> provision(Label label, int excessWorkload) {
        try {

            final SlaveTemplate t = getTemplate(label);

            List<PlannedNode> r = new ArrayList<PlannedNode>();
            for( ; excessWorkload>0; excessWorkload-- ) {
                if(countCurrentEC2Slaves()>=instanceCap) {
                    LOGGER.log(Level.INFO, "Instance cap reached, not provisioning.");
                    break;      // maxed out
                }

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
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING,"Failed to count the # of live instances on EC2",e);
            return Collections.emptyList();
        }
    }

    @Override
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
     * Connects to EC2 and returns {@link AmazonEC2}, which can then be used to communicate with EC2.
     */
    public synchronized AmazonEC2 connect() throws AmazonClientException {
        try {
            if (connection == null) {
                connection = connect(accessId, secretKey, getEc2EndpointUrl());
            }
            return connection;
        } catch (IOException e) {
            throw new AmazonClientException("Failed to retrieve the endpoint",e);
        }
    }

    /***
     * Connect to an EC2 instance.
     * @return {@link AmazonEC2} client
     */
    public static AmazonEC2 connect(String accessId, String secretKey, URL endpoint) {
        return connect(accessId, Secret.fromString(secretKey), endpoint);
    }

    /***
     * Connect to an EC2 instance.
     * @return {@link AmazonEC2} client
     */
    public static AmazonEC2 connect(String accessId, Secret secretKey, URL endpoint) {
    	awsCredentials = new BasicAWSCredentials(accessId, Secret.toString(secretKey));
        AmazonEC2 client = new AmazonEC2Client(awsCredentials);
        client.setEndpoint(endpoint.toString());
        return client;
    }

    /***
     * Convert a configured hostname like 'us-east-1' to a FQDN or ip address
     */
    public static String convertHostName(String ec2HostName) {
        if (ec2HostName == null || ec2HostName.length()==0)
            ec2HostName = DEFAULT_EC2_HOST;
        if (!ec2HostName.contains("."))
            ec2HostName = ec2HostName + "." + EC2_URL_HOST;
	return ec2HostName;
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
     * Computes the presigned URL for the given S3 resource.
     *
     * @param path
     *      String like "/bucketName/folder/folder/abc.txt" that represents the resource to request.
     */
    public URL buildPresignedURL(String path) throws IOException, AmazonClientException {
        long expires = System.currentTimeMillis()+60*60*1000;
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(path, Secret.toString(secretKey));
        request.setExpiration(new Date(expires));
        AmazonS3 s3 = new AmazonS3Client(awsCredentials);
        return s3.generatePresignedUrl(request);
    }

    /* Parse a url or return a sensible error */
    public static URL checkEndPoint(String url) throws FormValidation {
        try {
            return new URL(url);
        } catch (MalformedURLException ex) {
            throw FormValidation.error("Endpoint URL is not a valid URL");
        }
    }


    public static abstract class DescriptorImpl extends Descriptor<Cloud> {
        public InstanceType[] getInstanceTypes() {
            return InstanceType.values();
        }

        public FormValidation doCheckAccessId(@QueryParameter String value) throws IOException, ServletException {
            return FormValidation.validateBase64(value,false,false,Messages.EC2Cloud_InvalidAccessId());
        }

        public FormValidation doCheckSecretKey(@QueryParameter String value) throws IOException, ServletException {
            return FormValidation.validateBase64(value,false,false,Messages.EC2Cloud_InvalidSecretKey());
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

        protected FormValidation doTestConnection( URL ec2endpoint,
                                     String accessId, String secretKey, String privateKey) throws IOException, ServletException {
            try {
                AmazonEC2 ec2 = connect(accessId, secretKey, ec2endpoint);
                ec2.describeInstances();

                if(accessId==null)
                    return FormValidation.error("Access ID is not specified");
                if(secretKey==null)
                    return FormValidation.error("Secret key is not specified");
                if(privateKey==null)
                    return FormValidation.error("Private key is not specified. Click 'Generate Key' to generate one.");

                if(privateKey.trim().length()>0) {
                    // check if this key exists
                    EC2PrivateKey pk = new EC2PrivateKey(privateKey);
                    if(pk.find(ec2)==null)
                        return FormValidation.error("The EC2 key pair private key isn't registered to this EC2 region (fingerprint is "+pk.getFingerprint()+")");
                }

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (AmazonClientException e) {
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential",e);
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doGenerateKey(StaplerResponse rsp, URL ec2EndpointUrl, String accessId, String secretKey
        ) throws IOException, ServletException {
            try {
                AmazonEC2 ec2 = connect(accessId, secretKey, ec2EndpointUrl);
                List<KeyPairInfo> existingKeys = ec2.describeKeyPairs().getKeyPairs();

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

                CreateKeyPairRequest request = new CreateKeyPairRequest("hudson-" + n);
                KeyPair key = ec2.createKeyPair(request).getKeyPair();


                rsp.addHeader("script","findPreviousFormItem(button,'privateKey').value='"+key.getKeyMaterial().replace("\n","\\n")+"'");

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (AmazonClientException e) {
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential",e);
                return FormValidation.error(e.getMessage());
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());

    private static boolean isSSL(URL endpoint) {
        return endpoint.getProtocol().equals("https");
    }

    private static int portFromURL(URL endpoint) {
        int ec2Port = endpoint.getPort();
        if (ec2Port == -1) {
            ec2Port = endpoint.getDefaultPort();
        }
        return ec2Port;
    }
}
