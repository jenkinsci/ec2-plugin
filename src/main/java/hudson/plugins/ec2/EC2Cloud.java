/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package hudson.plugins.ec2;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.Domain;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.ProxyConfiguration;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.servlet.ServletException;

import hudson.model.TaskListener;
import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.amazonaws.AmazonClientException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;

import hudson.ProxyConfiguration;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;

/**
 * Hudson's view of EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2Cloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());

    public static final String DEFAULT_EC2_HOST = "us-east-1";

    public static final String AWS_URL_HOST = "amazonaws.com";

    public static final String EC2_SLAVE_TYPE_SPOT = "spot";

    public static final String EC2_SLAVE_TYPE_DEMAND = "demand";

    private static final SimpleFormatter sf = new SimpleFormatter();

    private final boolean useInstanceProfileForCredentials;

    /**
     * Id of the {@link AmazonWebServicesCredentials} used to connect to Amazon ECS
     */
    @CheckForNull
    private String credentialsId;
    @CheckForNull
    @Deprecated
    private transient String accessId;
    @CheckForNull
    @Deprecated
    private transient Secret secretKey;

    protected final EC2PrivateKey privateKey;

    /**
     * Upper bound on how many instances we may provision.
     */
    public final int instanceCap;

    private final List<? extends SlaveTemplate> templates;

    private transient KeyPair usableKeyPair;

    protected transient AmazonEC2 connection;

    private static AWSCredentialsProvider awsCredentialsProvider;

    protected EC2Cloud(String id, boolean useInstanceProfileForCredentials, String credentialsId, String privateKey,
            String instanceCapStr, List<? extends SlaveTemplate> templates) {
        super(id);
        this.useInstanceProfileForCredentials = useInstanceProfileForCredentials;
        this.credentialsId = credentialsId;
        this.privateKey = new EC2PrivateKey(privateKey);

        if (templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        if (instanceCapStr.isEmpty()) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }

        readResolve(); // set parents
    }

    public abstract URL getEc2EndpointUrl() throws IOException;

    public abstract URL getS3EndpointUrl() throws IOException;

    protected Object readResolve() {
        for (SlaveTemplate t : templates)
            t.parent = this;
        if (this.accessId != null && credentialsId == null) {
            // REPLACE this.accessId and this.secretId by a credential

            SystemCredentialsProvider systemCredentialsProvider = SystemCredentialsProvider.getInstance();
            // ITERATE ON EXISTING CREDS AND DON'T CREATE IF EXIST
            for (Credentials credentials: systemCredentialsProvider.getCredentials()) {
                if (credentials instanceof AmazonWebServicesCredentials) {
                    AmazonWebServicesCredentials awsCreds = (AmazonWebServicesCredentials) credentials;
                    AWSCredentials awsCredentials = awsCreds.getCredentials();
                    if (accessId.equals(awsCredentials.getAWSAccessKeyId()) &&
                            Secret.toString(this.secretKey).equals(awsCredentials.getAWSSecretKey())) {

                        this.credentialsId = awsCreds.getId();
                        this.accessId = null;
                        this.secretKey = null;
                        return this;
                    }
                }
            }
            // CREATE
            for (CredentialsStore credentialsStore: CredentialsProvider.lookupStores(Jenkins.getInstance())) {

                if (credentialsStore instanceof  SystemCredentialsProvider.StoreImpl) {

                    try {
                        String credsId = UUID.randomUUID().toString();
                        credentialsStore.addCredentials(Domain.global(), new AWSCredentialsImpl(
                                CredentialsScope.SYSTEM,
                                credsId,
                                this.accessId,
                                this.secretKey.getEncryptedValue(),
                                "EC2 Cloud - " + getDisplayName()));
                        this.credentialsId = credsId;
                        this.accessId = null;
                        this.secretKey = null;
                        return this;
                    } catch (IOException e) {
                        this.credentialsId = null;
                        LOGGER.log(Level.WARNING, "Exception converting legacy configuration to the new credentials API", e);
                    }
                }

            }
            // PROBLEM, GLOBAL STORE NOT FOUND
            LOGGER.log(Level.WARNING, "EC2 Plugin could not migrate credentials to the Jenkins Global Credentials Store, EC2 Plugin for cloud {0} must be manually reconfigured", getDisplayName());
        }
        return this;
    }

    public boolean isUseInstanceProfileForCredentials() {
        return useInstanceProfileForCredentials;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public EC2PrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getInstanceCapStr() {
        if (instanceCap == Integer.MAX_VALUE)
            return "";
        else
            return String.valueOf(instanceCap);
    }

    public List<SlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    public SlaveTemplate getTemplate(String template) {
        for (SlaveTemplate t : templates) {
            if (t.description.equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets {@link SlaveTemplate} that has the matching {@link Label}.
     */
    public SlaveTemplate getTemplate(Label label) {
        for (SlaveTemplate t : templates) {
            if (t.getMode() == Node.Mode.NORMAL) {
                if (label == null || label.matches(t.getLabelSet())) {
                    return t;
                }
            } else if (t.getMode() == Node.Mode.EXCLUSIVE) {
                if (label != null && label.matches(t.getLabelSet())) {
                    return t;
                }
            }
        }
        return null;
    }

    /**
     * Gets the {@link KeyPairInfo} used for the launch.
     */
    public synchronized KeyPair getKeyPair() throws AmazonClientException, IOException {
        if (usableKeyPair == null)
            usableKeyPair = privateKey.find(connect());
        return usableKeyPair;
    }

    /**
     * Debug command to attach to a running instance.
     */
    public void doAttach(StaplerRequest req, StaplerResponse rsp, @QueryParameter String id)
            throws ServletException, IOException, AmazonClientException {
        checkPermission(PROVISION);
        SlaveTemplate t = getTemplates().get(0);

        StringWriter sw = new StringWriter();
        StreamTaskListener listener = new StreamTaskListener(sw);
        EC2AbstractSlave node = t.attach(id, listener);
        Jenkins.getInstance().addNode(node);

        rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
    }

    public HttpResponse doProvision(@QueryParameter String template) throws ServletException, IOException {
        checkPermission(PROVISION);
        if (template == null) {
            throw HttpResponses.error(SC_BAD_REQUEST, "The 'template' query parameter is missing");
        }
        SlaveTemplate t = getTemplate(template);
        if (t == null) {
            throw HttpResponses.error(SC_BAD_REQUEST, "No such template: " + template);
        }

        try {
            EC2AbstractSlave node = getNewOrExistingAvailableSlave(t, null, true, null);
            if (node == null)
                throw HttpResponses.error(SC_BAD_REQUEST, "Cloud or AMI instance cap would be exceeded for: " + template);
            Jenkins.getInstance().addNode(node);

            return HttpResponses.redirectViaContextPath("/computer/" + node.getNodeName());
        } catch (AmazonClientException e) {
            throw HttpResponses.error(SC_INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * Counts the number of instances in EC2 that can be used with the specified image and a template. Also removes any
     * nodes associated with canceled requests.
     *
     * @param template If left null, then all instances are counted.
     */
    private int countCurrentEC2Slaves(SlaveTemplate template) throws AmazonClientException {
        LOGGER.log(Level.FINE, "Counting current slaves: " + (template != null ? (" AMI: " + template.getAmi()) : " All AMIS"));
        int n = 0;
        Set<String> instanceIds = new HashSet<String>();
        String description = template != null ? template.description : null;

        for (Reservation r : connect().describeInstances().getReservations()) {
            for (Instance i : r.getInstances()) {
                if (isEc2ProvisionedAmiSlave(i.getTags(), description) && (template == null
                        || template.getAmi().equals(i.getImageId()))) {
                    InstanceStateName stateName = InstanceStateName.fromValue(i.getState().getName());
                    if (stateName != InstanceStateName.Terminated && stateName != InstanceStateName.ShuttingDown) {
                        LOGGER.log(Level.FINE, "Existing instance found: " + i.getInstanceId() + " AMI: " + i.getImageId()
                                + " Template: " + description);
                        n++;
                        instanceIds.add(i.getInstanceId());
                    }
                }
            }
        }
        List<SpotInstanceRequest> sirs = null;
        List<Filter> filters = new ArrayList<Filter>();
        List<String> values;
        if (template != null) {
            values = new ArrayList<String>();
            values.add(template.getAmi());
            filters.add(new Filter("launch.image-id", values));
        }

        values = new ArrayList<String>();
        values.add(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE);
        filters.add(new Filter("tag-key", values));

        DescribeSpotInstanceRequestsRequest dsir = new DescribeSpotInstanceRequestsRequest().withFilters(filters);
        try {
            sirs = connect().describeSpotInstanceRequests(dsir).getSpotInstanceRequests();
        } catch (Exception ex) {
            // Some ec2 implementations don't implement spot requests (Eucalyptus)
            LOGGER.log(Level.FINEST, "Describe spot instance requests failed", ex);
        }
        Set<SpotInstanceRequest> sirSet = new HashSet();

        if (sirs != null) {
            for (SpotInstanceRequest sir : sirs) {
                sirSet.add(sir);
                if (sir.getState().equals("open") || sir.getState().equals("active")) {
                    if (sir.getInstanceId() != null && instanceIds.contains(sir.getInstanceId()))
                        continue;

                    LOGGER.log(Level.FINE, "Spot instance request found: " + sir.getSpotInstanceRequestId() + " AMI: "
                            + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus());
                    n++;
                    
                    if (sir.getInstanceId() != null)
                        instanceIds.add(sir.getInstanceId());
                } else {
                    // Canceled or otherwise dead
                    for (Node node : Jenkins.getInstance().getNodes()) {
                        try {
                            if (!(node instanceof EC2SpotSlave))
                                continue;
                            EC2SpotSlave ec2Slave = (EC2SpotSlave) node;
                            if (ec2Slave.getSpotInstanceRequestId().equals(sir.getSpotInstanceRequestId())) {
                                LOGGER.log(Level.INFO, "Removing dead request: " + sir.getSpotInstanceRequestId() + " AMI: "
                                        + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus());
                                Jenkins.getInstance().removeNode(node);
                                break;
                            }
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to remove node for dead request: " + sir.getSpotInstanceRequestId()
                                            + " AMI: " + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus(),
                                    e);
                        }
                    }
                }
            }
        }

        // Count nodes where the spot request does not yet exist (sometimes it takes time for the request to appear
        // in the EC2 API)
        for (Node node : Jenkins.getInstance().getNodes()) {
            if (!(node instanceof EC2SpotSlave))
                continue;
            EC2SpotSlave ec2Slave = (EC2SpotSlave) node;
            SpotInstanceRequest sir = ec2Slave.getSpotRequest();

            if (sir == null) {
                LOGGER.log(Level.FINE, "Found spot node without request: " + ec2Slave.getSpotInstanceRequestId());
                n++;
                continue;
            }

            if (sirSet.contains(sir))
                continue;

            sirSet.add(sir);

            if (sir.getState().equals("open") || sir.getState().equals("active")) {
                if (template != null) {
                    List<Tag> instanceTags = sir.getTags();
                    for (Tag tag : instanceTags) {
                        if (StringUtils.equals(tag.getKey(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE) && StringUtils.equals(tag.getValue(), getSlaveTypeTagValue(EC2_SLAVE_TYPE_SPOT, template.description)) && sir.getLaunchSpecification().getImageId().equals(template.getAmi())) {
                        
                            if (sir.getInstanceId() != null && instanceIds.contains(sir.getInstanceId()))
                                continue;
                
                            LOGGER.log(Level.FINE, "Spot instance request found (from node): " + sir.getSpotInstanceRequestId() + " AMI: "
                                    + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus());
                            n++;
                            
                            if (sir.getInstanceId() != null)
                                instanceIds.add(sir.getInstanceId());
                        }
                    }
                }
            }
        }

        return n;
    }

    private boolean isEc2ProvisionedAmiSlave(List<Tag> tags, String description) {
        for (Tag tag : tags) {
            if (StringUtils.equals(tag.getKey(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)) {
                if (description == null) {
                    return true;
                } else if (StringUtils.equals(tag.getValue(), EC2Cloud.EC2_SLAVE_TYPE_DEMAND)
                        || StringUtils.equals(tag.getValue(), EC2Cloud.EC2_SLAVE_TYPE_SPOT)) {
                    // To handle cases where description is null and also upgrade cases for existing slave nodes.
                    return true;
                } else if (StringUtils.equals(tag.getValue(), getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_DEMAND, description))
                        || StringUtils.equals(tag.getValue(), getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_SPOT, description))) {
                    return true;
                } else {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Returns the maximum number of possible slaves that can be created.
     */
    private int getPossibleNewSlavesCount(SlaveTemplate template) throws AmazonClientException {
        int estimatedTotalSlaves = countCurrentEC2Slaves(null);
        int estimatedAmiSlaves = countCurrentEC2Slaves(template);

        int availableTotalSlaves = instanceCap - estimatedTotalSlaves;
        int availableAmiSlaves = template.getInstanceCap() - estimatedAmiSlaves;
        LOGGER.log(Level.FINE, "Available Total Slaves: " + availableTotalSlaves + " Available AMI slaves: " + availableAmiSlaves
                + " AMI: " + template.getAmi() + " TemplateDesc: " + template.description);

        return Math.min(availableAmiSlaves, availableTotalSlaves);
    }

    /**
     * Obtains a slave whose AMI matches the AMI of the given template, and that also has requiredLabel (if requiredLabel is non-null)
     * forceCreateNew specifies that the creation of a new slave is required. Otherwise, an existing matching slave may be re-used
     * @param alreadyLaunched 
     */
    private synchronized EC2AbstractSlave getNewOrExistingAvailableSlave(SlaveTemplate template, Label requiredLabel, boolean forceCreateNew, List<String> alreadyLaunched) {
        /*
         * Note this is synchronized between counting the instances and then allocating the node. Once the node is
         * allocated, we don't look at that instance as available for provisioning.
         */
        int possibleSlavesCount = getPossibleNewSlavesCount(template);
        if (possibleSlavesCount < 0) {
            LOGGER.log(Level.INFO, "Cannot provision - no capacity for instances: " + possibleSlavesCount);
            return null;
        }

        try {
            EnumSet<SlaveTemplate.ProvisionOptions> provisionOptions = EnumSet.noneOf(SlaveTemplate.ProvisionOptions.class);
            if (forceCreateNew)
                provisionOptions = EnumSet.of(SlaveTemplate.ProvisionOptions.FORCE_CREATE);
            else if (possibleSlavesCount > 0)
                provisionOptions = EnumSet.of(SlaveTemplate.ProvisionOptions.ALLOW_CREATE);
            
            return template.provision(StreamTaskListener.fromStdout(), requiredLabel, provisionOptions, alreadyLaunched);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Exception during provisioning", e);
            return null;
        }
    }

    @Override
    public Collection<PlannedNode> provision(Label label, int excessWorkload) {
        try {
            List<PlannedNode> r = new ArrayList<PlannedNode>();
            List<String> launched = new ArrayList<String>();
            final SlaveTemplate t = getTemplate(label);
            LOGGER.log(Level.INFO, "Attempting to provision slave from template " + t + " needed by excess workload of " + excessWorkload + " units of label '" + label + "'");
            if (label == null) {
                LOGGER.log(Level.WARNING, String.format("Label is null - can't calculate how many executors slave will have. Using %s number of executors", t.getNumExecutors()));
            }
            while (excessWorkload > 0) {

                final EC2AbstractSlave slave = getNewOrExistingAvailableSlave(t, label, false, launched);
                // Returned null if a new node could not be created
                if (slave == null)
                    break;
                LOGGER.log(Level.INFO, String.format("We have now %s computers", Jenkins.getInstance().getComputers().length));
                if (t.isNode()) {
                    Jenkins.getInstance().addNode(slave);
                    LOGGER.log(Level.INFO, String.format("Added node named: %s, We have now %s computers", slave.getNodeName(), Jenkins.getInstance().getComputers().length));
                    r.add(new PlannedNode(t.getDisplayName(), Computer.threadPoolForRemoting.submit(new Callable<Node>() {

                        public Node call() throws Exception {
                            long startTime = System.currentTimeMillis(); // fetch starting time
                            while ((System.currentTimeMillis() - startTime) < slave.launchTimeout * 1000) {
                                return tryToCallSlave(slave, t);
                            }
                            LOGGER.log(Level.WARNING, "Expected - Instance - failed to connect within launch timeout");
                            return tryToCallSlave(slave, t);
                        }
                    }), t.getNumExecutors()));
                    
                    launched.add(slave.getInstanceId());
                }

                excessWorkload -= t.getNumExecutors();
            }
            LOGGER.log(Level.INFO, "Attempting provision - finished, excess workload: " + excessWorkload);
            return r;
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING, "Exception during provisioning", e);
            return Collections.emptyList();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Exception during provisioning", e);
            return Collections.emptyList();
        }
    }

    private EC2AbstractSlave tryToCallSlave(EC2AbstractSlave slave, SlaveTemplate template) {
    	try {
            slave.toComputer().connect(false).get();
        } catch (Exception e) {
            if (template.spotConfig != null) {
            	if(StringUtils.isNotEmpty(slave.getInstanceId()) && slave.isConnected) {
            		LOGGER.log(Level.INFO, String.format("Instance id: %s for node: %s is connected now.", slave.getInstanceId(), slave.getNodeName()));
            		return slave;
            	}
            }
        }
    	return slave;
    }

    @Override
    public boolean canProvision(Label label) {
        return getTemplate(label) != null;
    }

    private AWSCredentialsProvider createCredentialsProvider() {
        return createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);
    }

    public static String getSlaveTypeTagValue(String slaveType, String templateDescription) {
        return templateDescription != null ? slaveType + "_" + templateDescription : slaveType;
    }

    public static AWSCredentialsProvider createCredentialsProvider(final boolean useInstanceProfileForCredentials, final String credentialsId) {

        if (useInstanceProfileForCredentials) {
            return new InstanceProfileCredentialsProvider();
        } else if (StringUtils.isBlank(credentialsId)) {
            return new DefaultAWSCredentialsProviderChain();
        } else {
            AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
            return new StaticCredentialsProvider(credentials.getCredentials());
        }
    }

    @CheckForNull
    private static AmazonWebServicesCredentials getCredentials(@Nullable String credentialsId) {
        if (StringUtils.isBlank(credentialsId)) {
            return null;
        }
        return (AmazonWebServicesCredentials) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(AmazonWebServicesCredentials.class, Jenkins.getInstance(),
                        ACL.SYSTEM, Collections.EMPTY_LIST),
                CredentialsMatchers.withId(credentialsId));
    }

    /**
     * Connects to EC2 and returns {@link AmazonEC2}, which can then be used to communicate with EC2.
     */
    public synchronized AmazonEC2 connect() throws AmazonClientException {
        try {
            if (connection == null) {
                connection = connect(createCredentialsProvider(), getEc2EndpointUrl());
            }
            return connection;
        } catch (IOException e) {
            throw new AmazonClientException("Failed to retrieve the endpoint", e);
        }
    }

    /***
     * Connect to an EC2 instance.
     *
     * @return {@link AmazonEC2} client
     */
    public synchronized static AmazonEC2 connect(AWSCredentialsProvider credentialsProvider, URL endpoint) {
        awsCredentialsProvider = credentialsProvider;
        ClientConfiguration config = new ClientConfiguration();
        config.setMaxErrorRetry(16); // Default retry limit (3) is low and often
        // cause problems. Raise it a bit.
        // See: https://issues.jenkins-ci.org/browse/JENKINS-26800
        config.setSignerOverride("AWS4SignerType");
        ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
        Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(endpoint.getHost());
        if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            config.setProxyHost(address.getHostName());
            config.setProxyPort(address.getPort());
            if (null != proxyConfig.getUserName()) {
                config.setProxyUsername(proxyConfig.getUserName());
                config.setProxyPassword(proxyConfig.getPassword());
            }
        }
        AmazonEC2 client = new AmazonEC2Client(credentialsProvider, config);
        client.setEndpoint(endpoint.toString());
        return client;
    }

    /***
     * Convert a configured hostname like 'us-east-1' to a FQDN or ip address
     */
    public static String convertHostName(String ec2HostName) {
        if (ec2HostName == null || ec2HostName.length() == 0)
            ec2HostName = DEFAULT_EC2_HOST;
        if (!ec2HostName.contains("."))
            ec2HostName = "ec2." + ec2HostName + "." + AWS_URL_HOST;
        return ec2HostName;
    }

    /***
     * Convert a user entered string into a port number "" -&gt; -1 to indicate default based on SSL setting
     */
    public static Integer convertPort(String ec2Port) {
        if (ec2Port == null || ec2Port.length() == 0)
            return -1;
        return Integer.parseInt(ec2Port);
    }

    /**
     * Computes the presigned URL for the given S3 resource.
     *
     * @param path String like "/bucketName/folder/folder/abc.txt" that represents the resource to request.
     */
    public URL buildPresignedURL(String path) throws AmazonClientException {
        AWSCredentials credentials = awsCredentialsProvider.getCredentials();
        long expires = System.currentTimeMillis() + 60 * 60 * 1000;
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(path, credentials.getAWSSecretKey());
        request.setExpiration(new Date(expires));
        AmazonS3 s3 = new AmazonS3Client(credentials);
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

        public FormValidation doCheckUseInstanceProfileForCredentials(@QueryParameter boolean value) {
            if (value) {
                try {
                    new InstanceProfileCredentialsProvider().getCredentials();
                } catch (AmazonClientException e) {
                    return FormValidation.error(Messages.EC2Cloud_FailedToObtainCredentailsFromEC2(), e.getMessage());
                }
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckPrivateKey(@QueryParameter String value) throws IOException, ServletException {
            boolean hasStart = false, hasEnd = false;
            BufferedReader br = new BufferedReader(new StringReader(value));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                    hasStart = true;
                if (line.equals("-----END RSA PRIVATE KEY-----"))
                    hasEnd = true;
            }
            if (!hasStart)
                return FormValidation.error("This doesn't look like a private key at all");
            if (!hasEnd)
                return FormValidation
                        .error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            return FormValidation.ok();
        }

        protected FormValidation doTestConnection(URL ec2endpoint, boolean useInstanceProfileForCredentials, String credentialsId, String privateKey)
                throws IOException, ServletException {
            try {
                AWSCredentialsProvider credentialsProvider = createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);
                AmazonEC2 ec2 = connect(credentialsProvider, ec2endpoint);
                ec2.describeInstances();

                if (privateKey == null)
                    return FormValidation.error("Private key is not specified. Click 'Generate Key' to generate one.");

                if (privateKey.trim().length() > 0) {
                    // check if this key exists
                    EC2PrivateKey pk = new EC2PrivateKey(privateKey);
                    if (pk.find(ec2) == null)
                        return FormValidation
                                .error("The EC2 key pair private key isn't registered to this EC2 region (fingerprint is "
                                        + pk.getFingerprint() + ")");
                }

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (AmazonClientException e) {
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential", e);
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doGenerateKey(StaplerResponse rsp, URL ec2EndpointUrl, boolean useInstanceProfileForCredentials, String credentialsId)
                throws IOException, ServletException {
            try {
                AWSCredentialsProvider credentialsProvider = createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);
                AmazonEC2 ec2 = connect(credentialsProvider, ec2EndpointUrl);
                List<KeyPairInfo> existingKeys = ec2.describeKeyPairs().getKeyPairs();

                int n = 0;
                while (true) {
                    boolean found = false;
                    for (KeyPairInfo k : existingKeys) {
                        if (k.getKeyName().equals("hudson-" + n))
                            found = true;
                    }
                    if (!found)
                        break;
                    n++;
                }

                CreateKeyPairRequest request = new CreateKeyPairRequest("hudson-" + n);
                KeyPair key = ec2.createKeyPair(request).getKeyPair();

                rsp.addHeader("script",
                        "findPreviousFormItem(button,'privateKey').value='" + key.getKeyMaterial().replace("\n", "\\n") + "'");

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (AmazonClientException e) {
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential", e);
                return FormValidation.error(e.getMessage());
            }
        }

        public ListBoxModel doFillCredentialsIdItems() {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                            CredentialsMatchers.always(),
                            CredentialsProvider.lookupCredentials(AmazonWebServicesCredentials.class,
                                    Jenkins.getInstance(),
                                    ACL.SYSTEM,
                                    Collections.EMPTY_LIST));
        }
    }

    public static void log(Logger logger, Level level, TaskListener listener, String message) {
        log(logger, level, listener, message, null);
    }

    public static void log(Logger logger, Level level, TaskListener listener, String message, Throwable exception) {
        logger.log(level, message, exception);
        if (listener != null) {
            if (exception != null)
                message += " Exception: " + exception;
            LogRecord lr = new LogRecord(level, message);
            PrintStream printStream = listener.getLogger();
            printStream.print(sf.format(lr));
        }
    }

}
