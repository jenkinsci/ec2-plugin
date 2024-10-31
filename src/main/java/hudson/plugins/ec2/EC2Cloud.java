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

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairResult;
import com.amazonaws.services.ec2.model.DeleteKeyPairRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
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
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.AbstractIdCredentialsListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import hudson.plugins.ec2.util.AmazonEC2Factory;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.lang.StringUtils;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

/**
 * Hudson's view of EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2Cloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());

    public static final String DEFAULT_EC2_HOST = "us-east-1";

    public static final String DEFAULT_EC2_ENDPOINT = "https://ec2.amazonaws.com";

    public static final String AWS_URL_HOST = "amazonaws.com";

    public static final String AWS_CN_URL_HOST = "amazonaws.com.cn";

    public static final String EC2_SLAVE_TYPE_SPOT = "spot";

    public static final String EC2_SLAVE_TYPE_DEMAND = "demand";

    public static final String EC2_REQUEST_EXPIRED_ERROR_CODE = "RequestExpired";

    private static final SimpleFormatter sf = new SimpleFormatter();

    private transient ReentrantLock slaveCountingLock = new ReentrantLock();

    private final boolean useInstanceProfileForCredentials;

    private final String roleArn;

    private final String roleSessionName;

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
    @CheckForNull
    @Deprecated
    private transient EC2PrivateKey privateKey;
    private String sshKeysCredentialsId;

    /**
     * Upper bound on how many instances we may provision.
     */
    private final int instanceCap;

    private List<? extends SlaveTemplate> templates;

    private transient KeyPair usableKeyPair;

    private transient volatile AmazonEC2 connection;

    protected EC2Cloud(String name, boolean useInstanceProfileForCredentials, String credentialsId, String privateKey, String sshKeysCredentialsId,
                       String instanceCapStr, List<? extends SlaveTemplate> templates, String roleArn, String roleSessionName) {
        super(name);
        this.useInstanceProfileForCredentials = useInstanceProfileForCredentials;
        this.roleArn = roleArn;
        this.roleSessionName = roleSessionName;
        this.credentialsId = Util.fixEmpty(credentialsId);
        this.sshKeysCredentialsId = Util.fixEmpty(sshKeysCredentialsId);

        if (templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        if (instanceCapStr == null || instanceCapStr.isEmpty()) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }

        readResolve(); // set parents
    }

    @Deprecated
    protected EC2Cloud(String id, boolean useInstanceProfileForCredentials, String credentialsId, String privateKey,
            String instanceCapStr, List<? extends SlaveTemplate> templates, String roleArn, String roleSessionName) {
        this(id, useInstanceProfileForCredentials, credentialsId, privateKey, null, instanceCapStr, templates, roleArn, roleSessionName);
    }

    @CheckForNull
    public KeyPair resolveKeyPair() throws IOException {
        KeyPair keyPair = null;
        LOGGER.fine(() -> "attempting to resolve static keypair");
        if (!sshKeysCredentialsId.isEmpty()) {
            LOGGER.fine(() -> "static keypair credential is configured, getting key");
            SSHUserPrivateKey privateKeyCredential = getSshCredential(sshKeysCredentialsId, Jenkins.get());
            if (privateKeyCredential != null) {
                EC2PrivateKey ec2PrivateKey = new  EC2PrivateKey(privateKeyCredential.getPrivateKey());
                keyPair = ec2PrivateKey.find(connect());
                LOGGER.fine("found matching keypair " + keyPair.getKeyName());
            }
        } else {
            LOGGER.fine(() -> "no ssh credential is configured, controller will use dynamic ssh keys");
        }
        return keyPair;
    }

    @CheckForNull
    public EC2PrivateKey resolvePrivateKey() throws IOException {
        if (!sshKeysCredentialsId.isEmpty()) {
            SSHUserPrivateKey privateKeyCredential = getSshCredential(sshKeysCredentialsId, Jenkins.get());
            if (privateKeyCredential != null) {
                LOGGER.fine("private key resolved from sshCredential");
                return new EC2PrivateKey(privateKeyCredential.getPrivateKey());
            }
            return null;
        } else {
            LOGGER.fine("controller is using dynamic ssh keys, nothing to resolve");
            return null;
        }
    }

    public abstract URL getEc2EndpointUrl() throws IOException;

    public abstract URL getS3EndpointUrl() throws IOException;

    public void addTemplate(SlaveTemplate newTemplate) throws Exception {
        String newTemplateDescription = newTemplate.description;
        if (getTemplate(newTemplateDescription) != null) throw new Exception(
                String.format("A SlaveTemplate with description %s already exists", newTemplateDescription));
        List<SlaveTemplate> templatesHolder = new ArrayList<>(templates);
        templatesHolder.add(newTemplate);
        templates = templatesHolder;
    }

    private void migratePrivateSshKeyToCredential(String privateKey) {
        // GET matching private key credential from Credential API if exists
        Optional<SSHUserPrivateKey> keyCredential = SystemCredentialsProvider.getInstance().getCredentials()
                .stream()
                .filter((cred) -> cred instanceof SSHUserPrivateKey)
                .filter((cred) -> ((SSHUserPrivateKey)cred).getPrivateKey().trim().equals(privateKey.trim()))
                .map(cred -> (SSHUserPrivateKey)cred)
                .findFirst();

        if (keyCredential.isPresent()){
            // SET this.sshKeysCredentialsId with the found credential
            sshKeysCredentialsId = keyCredential.get().getId();
        } else {
            // CREATE new credential
            String credsId = UUID.randomUUID().toString();

            SSHUserPrivateKey sshKeyCredentials = new BasicSSHUserPrivateKey(CredentialsScope.SYSTEM, credsId, "key",
                    new BasicSSHUserPrivateKey.PrivateKeySource() {
                        @NonNull
                        @Override
                        public List<String> getPrivateKeys() {
                            return Collections.singletonList(privateKey.trim());
                        }
                    }, "", "EC2 Cloud Private Key - " + getDisplayName());

            addNewGlobalCredential(sshKeyCredentials);

            sshKeysCredentialsId = credsId;
        }
    }

    protected Object readResolve() {
        this.slaveCountingLock = new ReentrantLock();

        for (SlaveTemplate t : templates)
            t.parent = this;

        if (this.sshKeysCredentialsId == null && this.privateKey != null ){
            migratePrivateSshKeyToCredential(this.privateKey.getPrivateKey());
        }
        this.privateKey = null; // This enforces it not to be persisted and that CasC will never output privateKey on export

        if (this.accessId != null && this.secretKey != null && credentialsId == null) {
            String secretKeyEncryptedValue = this.secretKey.getEncryptedValue();
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
            String credsId = UUID.randomUUID().toString();
            addNewGlobalCredential(new AWSCredentialsImpl(
                    CredentialsScope.SYSTEM, credsId, this.accessId, secretKeyEncryptedValue,
                    "EC2 Cloud - " + getDisplayName()));

            this.credentialsId = credsId;
            this.accessId = null;
            this.secretKey = null;


            // PROBLEM, GLOBAL STORE NOT FOUND
            LOGGER.log(Level.WARNING, "EC2 Plugin could not migrate credentials to the Jenkins Global Credentials Store, EC2 Plugin for cloud {0} must be manually reconfigured", getDisplayName());
        }

        return this;
    }

    private void addNewGlobalCredential(Credentials credentials){
        for (CredentialsStore credentialsStore: CredentialsProvider.lookupStores(Jenkins.get())) {

            if (credentialsStore instanceof  SystemCredentialsProvider.StoreImpl) {

                try {
                    credentialsStore.addCredentials(Domain.global(), credentials);
                } catch (IOException e) {
                    this.credentialsId = null;
                    LOGGER.log(Level.WARNING, "Exception converting legacy configuration to the new credentials API", e);
                }
            }

        }
    }

    public boolean isUseInstanceProfileForCredentials() {
        return useInstanceProfileForCredentials;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public String getRoleSessionName() {
        return roleSessionName;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @CheckForNull
    public String getSshKeysCredentialsId() {
        return sshKeysCredentialsId;
    }

    @Deprecated
    public EC2PrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getInstanceCapStr() {
        if (instanceCap == Integer.MAX_VALUE)
            return "";
        else
            return String.valueOf(instanceCap);
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public List<SlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    @CheckForNull
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
    @Deprecated
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
     * Gets list of {@link SlaveTemplate} that matches {@link Label}.
     */
    public Collection<SlaveTemplate> getTemplates(Label label) {
        List<SlaveTemplate> matchingTemplates = new ArrayList<>();
        for (SlaveTemplate t : templates) {
            if (t.getMode() == Node.Mode.NORMAL) {
                if (label == null || label.matches(t.getLabelSet())) {
                    matchingTemplates.add(t);
                }
            } else if (t.getMode() == Node.Mode.EXCLUSIVE) {
                if (label != null && label.matches(t.getLabelSet())) {
                    matchingTemplates.add(t);
                }
            }
        }
        return matchingTemplates;
    }

    /**
     * Gets the {@link KeyPairInfo} used for the launch.
     */
    @CheckForNull
    public synchronized KeyPair getKeyPair() throws AmazonClientException, IOException {

        if (usableKeyPair == null) {
            LOGGER.fine("no usable keypair found, will attempt to resolve");
            this.usableKeyPair = resolveKeyPair();
        }
        LOGGER.fine(() -> "usable keypair already present -> " + usableKeyPair);
        return usableKeyPair;
    }

    /**
     * Debug command to attach to a running instance.
     */
    @RequirePOST
    public void doAttach(StaplerRequest req, StaplerResponse rsp, @QueryParameter String id)
            throws ServletException, IOException, AmazonClientException {
        checkPermission(PROVISION);
        SlaveTemplate t = getTemplates().get(0);

        StringWriter sw = new StringWriter();
        StreamTaskListener listener = new StreamTaskListener(sw);
        EC2AbstractSlave node = t.attach(id, listener);
        Jenkins.get().addNode(node);

        rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
    }

    @RequirePOST
    public HttpResponse doProvision(@QueryParameter String template) throws ServletException, IOException {
        checkPermission(PROVISION);
        if (template == null) {
            throw HttpResponses.error(SC_BAD_REQUEST, "The 'template' query parameter is missing");
        }
        SlaveTemplate t = getTemplate(template);
        if (t == null) {
            throw HttpResponses.error(SC_BAD_REQUEST, "No such template: " + template);
        }

        final Jenkins jenkinsInstance = Jenkins.get();
        if (jenkinsInstance.isQuietingDown()) {
            throw HttpResponses.error(SC_BAD_REQUEST, "Jenkins instance is quieting down");
        }
        if (jenkinsInstance.isTerminating()) {
            throw HttpResponses.error(SC_BAD_REQUEST, "Jenkins instance is terminating");
        }
        try {
            List<EC2AbstractSlave> nodes = getNewOrExistingAvailableSlave(t, 1, true);
            if (nodes == null || nodes.isEmpty())
                throw HttpResponses.error(SC_BAD_REQUEST, "Cloud or AMI instance cap would be exceeded for: " + template);

            //Reconnect a stopped instance, the ADD is invoking the connect only for the node creation
            Computer c = nodes.get(0).toComputer();
            if (nodes.get(0).getStopOnTerminate() && c !=  null) {
                c.connect(false);
            }
            jenkinsInstance.addNode(nodes.get(0));

            return HttpResponses.redirectViaContextPath("/computer/" + nodes.get(0).getNodeName());
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
        String jenkinsServerUrl = JenkinsLocationConfiguration.get().getUrl();

        if (jenkinsServerUrl == null) {
            LOGGER.log(Level.WARNING, "No Jenkins server URL specified, it is strongly recommended to open /configure and set the server URL. " +
                    "Not having has disabled the per-controller instance cap counting (cf. https://github.com/jenkinsci/ec2-plugin/pull/310)");
        }

        LOGGER.log(Level.FINE, "Counting current agents: "
            + (template != null ? (" AMI: " + template.getAmi() + " TemplateDesc: " + template.description) : " All AMIS")
            + " Jenkins Server: " + jenkinsServerUrl);
        int n = 0;
        Set<String> instanceIds = new HashSet<>();
        String description = template != null ? template.description : null;

        List<Filter> filters = getGenericFilters(jenkinsServerUrl, template);
        filters.add(new Filter("instance-state-name").withValues("running", "pending", "stopping"));
        DescribeInstancesRequest dir = new DescribeInstancesRequest().withFilters(filters);
        DescribeInstancesResult result = null;
        do {
            result = connect().describeInstances(dir);
            dir.setNextToken(result.getNextToken());
            for (Reservation r : result.getReservations()) {
                for (Instance i : r.getInstances()) {
                    if (isEc2ProvisionedAmiSlave(i.getTags(), description)) {
                        LOGGER.log(Level.FINE, "Existing instance found: " + i.getInstanceId() + " AMI: " + i.getImageId()
                        + (template != null ? (" Template: " + description) : "") + " Jenkins Server: " + jenkinsServerUrl);
                        n++;
                        instanceIds.add(i.getInstanceId());
                    }
                }
            }
        } while(result.getNextToken() != null);

        n += countCurrentEC2SpotSlaves(template, jenkinsServerUrl, instanceIds);

        return n;
    }

    /**
     * Counts the number of EC2 Spot instances that can be used with the specified image and a template. Also removes any
     * nodes associated with canceled requests.
     *
     * @param template If left null, then all spot instances are counted.
     */
    private int countCurrentEC2SpotSlaves(SlaveTemplate template, String jenkinsServerUrl, Set<String> instanceIds) throws AmazonClientException {
        int n = 0;
        String description = template != null ? template.description : null;
        List<SpotInstanceRequest> sirs = null;
        List<Filter> filters = getGenericFilters(jenkinsServerUrl, template);
        if (template != null) {
            filters.add(new Filter("launch.image-id").withValues(template.getAmi()));
        }

        DescribeSpotInstanceRequestsRequest dsir = new DescribeSpotInstanceRequestsRequest().withFilters(filters).withMaxResults(100);
        Set<SpotInstanceRequest> sirSet = new HashSet<>();
        DescribeSpotInstanceRequestsResult sirResp = null;

        do {
            try {
                sirResp = connect().describeSpotInstanceRequests(dsir);
                sirs = sirResp.getSpotInstanceRequests();
                dsir.setNextToken(sirResp.getNextToken());
            } catch (Exception ex) {
                // Some ec2 implementations don't implement spot requests (Eucalyptus)
                LOGGER.log(Level.FINEST, "Describe spot instance requests failed", ex);
                break;
            }

            if (sirs != null) {
                for (SpotInstanceRequest sir : sirs) {
                    sirSet.add(sir);
                    if (sir.getState().equals("open") || sir.getState().equals("active")) {
                        if (sir.getInstanceId() != null && instanceIds.contains(sir.getInstanceId()))
                            continue;

                        if (isEc2ProvisionedAmiSlave(sir.getTags(), description)) {
                            LOGGER.log(Level.FINE, "Spot instance request found: " + sir.getSpotInstanceRequestId() + " AMI: "
                                    + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus());

                            n++;
                            if (sir.getInstanceId() != null)
                                instanceIds.add(sir.getInstanceId());
                        }
                    } else {
                        // Cancelled or otherwise dead
                        for (Node node : Jenkins.get().getNodes()) {
                            try {
                                if (!(node instanceof EC2SpotSlave))
                                    continue;
                                EC2SpotSlave ec2Slave = (EC2SpotSlave) node;
                                if (ec2Slave.getSpotInstanceRequestId().equals(sir.getSpotInstanceRequestId())) {
                                    LOGGER.log(Level.INFO, "Removing dead request: " + sir.getSpotInstanceRequestId() + " AMI: "
                                            + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus());
                                    Jenkins.get().removeNode(node);
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
        } while(sirResp.getNextToken() != null);
        n += countJenkinsNodeSpotInstancesWithoutRequests(template, sirSet, instanceIds);
        return n;
    }

    // Count nodes where the spot request does not yet exist (sometimes it takes time for the request to appear
    // in the EC2 API)
    private int countJenkinsNodeSpotInstancesWithoutRequests(SlaveTemplate template, Set<SpotInstanceRequest> sirSet, Set<String> instanceIds) throws AmazonClientException {
        int n = 0;
        for (Node node : Jenkins.get().getNodes()) {
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


    private List<Filter> getGenericFilters(String jenkinsServerUrl, SlaveTemplate template) {
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("tag-key").withValues(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE));
        if (jenkinsServerUrl != null) {
            // The instances must match the jenkins server url
            filters.add(new Filter("tag:" + EC2Tag.TAG_NAME_JENKINS_SERVER_URL).withValues(jenkinsServerUrl));
        } else {
            filters.add(new Filter("tag-key").withValues(EC2Tag.TAG_NAME_JENKINS_SERVER_URL));
        }

        if (template != null) {
            List<EC2Tag> tags = template.getTags();
            if (tags != null) {
                for (EC2Tag tag : tags) {
                    if (tag.getName() != null && tag.getValue() != null) {
                        filters.add(new Filter("tag:" + tag.getName()).withValues(tag.getValue()));
                    }
                }
            }
        }
        return filters;
    }

    private boolean isEc2ProvisionedAmiSlave(List<Tag> tags, String description) {
        for (Tag tag : tags) {
            if (StringUtils.equals(tag.getKey(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)) {
                if (description == null) {
                    return true;
                } else if (StringUtils.equals(tag.getValue(), EC2Cloud.EC2_SLAVE_TYPE_DEMAND)
                        || StringUtils.equals(tag.getValue(), EC2Cloud.EC2_SLAVE_TYPE_SPOT)) {
                    // To handle cases where description is null and also upgrade cases for existing agent nodes.
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
     * Returns the maximum number of possible agents that can be created.
     */
    private int getPossibleNewSlavesCount(SlaveTemplate template) throws AmazonClientException {
        int estimatedTotalSlaves = countCurrentEC2Slaves(null);
        int estimatedAmiSlaves = countCurrentEC2Slaves(template);

        int availableTotalSlaves = instanceCap - estimatedTotalSlaves;
        int availableAmiSlaves = template.getInstanceCap() - estimatedAmiSlaves;
        LOGGER.log(Level.FINE, "Available Total Agents: " + availableTotalSlaves + " Available AMI agents: " + availableAmiSlaves
                + " AMI: " + template.getAmi() + " TemplateDesc: " + template.description);

        return Math.min(availableAmiSlaves, availableTotalSlaves);
    }

    /**
     * Obtains a agent whose AMI matches the AMI of the given template, and that also has requiredLabel (if requiredLabel is non-null)
     * forceCreateNew specifies that the creation of a new agent is required. Otherwise, an existing matching agent may be re-used
     */
    private List<EC2AbstractSlave> getNewOrExistingAvailableSlave(SlaveTemplate t, int number, boolean forceCreateNew) throws IOException {
        try {
            slaveCountingLock.lock();
            int possibleSlavesCount = getPossibleNewSlavesCount(t);
            if (possibleSlavesCount <= 0) {
                LOGGER.log(Level.INFO, "{0}. Cannot provision - no capacity for instances: " + possibleSlavesCount, t);
                return null;
            }

            EnumSet<SlaveTemplate.ProvisionOptions> provisionOptions;
            if (forceCreateNew)
                provisionOptions = EnumSet.of(SlaveTemplate.ProvisionOptions.FORCE_CREATE);
            else
                provisionOptions = EnumSet.of(SlaveTemplate.ProvisionOptions.ALLOW_CREATE);

            if (number > possibleSlavesCount) {
                LOGGER.log(Level.INFO, String.format("%d nodes were requested for the template %s, " +
                        "but because of instance cap only %d can be provisioned", number, t, possibleSlavesCount));
                number = possibleSlavesCount;
            }

            return t.provision(number, provisionOptions);
        } finally { slaveCountingLock.unlock(); }
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        final Collection<SlaveTemplate> matchingTemplates = getTemplates(label);
        List<PlannedNode> plannedNodes = new ArrayList<>();

        Jenkins jenkinsInstance = Jenkins.get();
        if (jenkinsInstance.isQuietingDown()) {
            LOGGER.log(Level.FINE, "Not provisioning nodes, Jenkins instance is quieting down");
            return Collections.emptyList();
        } else if (jenkinsInstance.isTerminating()) {
            LOGGER.log(Level.FINE, "Not provisioning nodes, Jenkins instance is terminating");
            return Collections.emptyList();
        }

        for (SlaveTemplate t : matchingTemplates) {
            try {
                LOGGER.log(Level.INFO, "{0}. Attempting to provision agent needed by excess workload of " + excessWorkload + " units", t);
                int number = Math.max(excessWorkload / t.getNumExecutors(), 1);
                final List<EC2AbstractSlave> slaves = getNewOrExistingAvailableSlave(t, number, false);

                if (slaves == null || slaves.isEmpty()) {
                    LOGGER.warning("Can't raise nodes for " + t);
                    continue;
                }

                for (final EC2AbstractSlave slave : slaves) {
                    if (slave == null) {
                        LOGGER.warning("Can't raise node for " + t);
                        continue;
                    }

                    plannedNodes.add(createPlannedNode(t, slave));
                    excessWorkload -= t.getNumExecutors();
                }

                LOGGER.log(Level.INFO, "{0}. Attempting provision finished, excess workload: " + excessWorkload, t);
                if (excessWorkload == 0) break;
            } catch (AmazonServiceException e) {
                LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
                if (e.getErrorCode().equals("RequestExpired")) {
                    // JENKINS-71554: A RequestExpired error can indicate that credentials have expired so reconnect
                    LOGGER.log(Level.INFO, "[JENKINS-71554] Reconnecting to EC2 due to RequestExpired error");
                    try {
                        reconnectToEc2();
                    } catch (IOException e2) {
                        LOGGER.log(Level.WARNING, "Failed to reconnect ec2", e2);
                    }
                }
            } catch (AmazonClientException | IOException e) {
                LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
            }
        }
        LOGGER.log(Level.INFO, "We have now {0} computers, waiting for {1} more",
            new Object[]{jenkinsInstance.getComputers().length, plannedNodes.size()});
        return plannedNodes;
    }

    private static void attachSlavesToJenkins(Jenkins jenkins, List<EC2AbstractSlave> slaves, SlaveTemplate t) throws IOException {
        for (final EC2AbstractSlave slave : slaves) {
            if (slave == null) {
                LOGGER.warning("Can't raise node for " + t);
                continue;
            }

            Computer c = slave.toComputer();
            if (slave.getStopOnTerminate() && c != null) {
                c.connect(false);
            }
            jenkins.addNode(slave);
        }
    }

    public void provision(SlaveTemplate t, int number) {

        Jenkins jenkinsInstance = Jenkins.get();
        if (jenkinsInstance.isQuietingDown()) {
            LOGGER.log(Level.FINE, "Not provisioning nodes, Jenkins instance is quieting down");
            return;
        } else if (jenkinsInstance.isTerminating()) {
            LOGGER.log(Level.FINE, "Not provisioning nodes, Jenkins instance is terminating");
            return;
        }

        try {
            LOGGER.log(Level.INFO, "{0}. Attempting to provision {1} agent(s)", new Object[]{t, number});
            final List<EC2AbstractSlave> slaves = getNewOrExistingAvailableSlave(t, number, false);

            if (slaves == null || slaves.isEmpty()) {
                LOGGER.warning("Can't raise nodes for " + t);
                return;
            }

            attachSlavesToJenkins(jenkinsInstance, slaves, t);

            LOGGER.log(Level.INFO, "{0}. Attempting provision finished", t);
            LOGGER.log(Level.INFO, "We have now {0} computers, waiting for {1} more",
              new Object[]{Jenkins.get().getComputers().length, number});
        } catch (AmazonClientException | IOException e) {
            LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
        }
    }

    /**
     * Helper method to reattach lost EC2 node agents @Issue("JENKINS-57795")
     *
     * @param jenkinsInstance Jenkins object that the nodes are to be re-attached to.
     * @param template The corresponding SlaveTemplate of the nodes that are to be re-attached
     * @param requestedNum The requested number of nodes to re-attach. We don't go above this in the case its value corresponds to an instance cap.
     */
    void attemptReattachOrphanOrStoppedNodes(Jenkins jenkinsInstance, SlaveTemplate template, int requestedNum) throws IOException {
        LOGGER.info("Attempting to wake & re-attach orphan/stopped nodes");
        AmazonEC2 ec2 = this.connect();
        DescribeInstancesResult diResult = template.getDescribeInstanceResult(ec2,true);
        List<Instance> orphansOrStopped = template.findOrphansOrStopped(diResult, requestedNum);
        template.wakeOrphansOrStoppedUp(ec2, orphansOrStopped);
        /* If the number of possible nodes to re-attach is greater than the number of nodes requested, will only attempt to re-attach up to the number requested */
        while (orphansOrStopped.size() > requestedNum) {
            orphansOrStopped.remove(0);
        }
        attachSlavesToJenkins(jenkinsInstance, template.toSlaves(InstanceInfo.fromInstances(orphansOrStopped, this)), template);
        if (orphansOrStopped.size() > 0) {
            LOGGER.info("Found and re-attached " + orphansOrStopped.size() + " orphan/stopped nodes");
        }
    }

    private PlannedNode createPlannedNode(final SlaveTemplate t, final EC2AbstractSlave slave) {
        return new PlannedNode(t.getDisplayName(),
                Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    int retryCount     = 0;
                    private static final int DESCRIBE_LIMIT = 2;
                    public Node call() throws Exception {
                        while (true) {
                            String instanceId = slave.getInstanceId();
                            if (slave instanceof EC2SpotSlave) {
                                if (((EC2SpotSlave) slave).isSpotRequestDead()) {
                                    LOGGER.log(Level.WARNING, "{0} Spot request died, can't do anything. Terminate provisioning", t);
                                    return null;
                                }

                                // Spot Instance does not have instance id yet.
                                if (StringUtils.isEmpty(instanceId)) {
                                    Thread.sleep(5000);
                                    continue;
                                }
                            }

                            Instance instance = CloudHelper.getInstanceWithRetry(instanceId, slave.getCloud());
                            if (instance == null) {
                                LOGGER.log(Level.WARNING, "{0} Can't find instance with instance id `{1}` in cloud {2}. Terminate provisioning ",
                                        new Object[]{t, instanceId, slave.cloudName});
                                return null;
                            }

                            InstanceStateName state = InstanceStateName.fromValue(instance.getState().getName());
                            if (state.equals(InstanceStateName.Running))  {
                                //Spot instance are not reconnected automatically,
                                // but could be new orphans that has the option enable
                                Computer c = slave.toComputer();
                                if (slave.getStopOnTerminate() && (c != null ))  {
                                    c.connect(false);
                                }

                                long startTime = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - instance.getLaunchTime().getTime());
                                LOGGER.log(Level.INFO, "{0} Node {1} moved to RUNNING state in {2} seconds and is ready to be connected by Jenkins",
                                        new Object[]{t, slave.getNodeName(), startTime});
                                return slave;
                            }

                            if (!state.equals(InstanceStateName.Pending)) {

                                if (retryCount >= DESCRIBE_LIMIT){
                                    LOGGER.log(Level.WARNING,"Instance {0} did not move to running after {1} attempts, terminating provisioning",
                                        new Object[]{instanceId, retryCount});
                                    return null;
                                }

                                LOGGER.log(Level.INFO, "Attempt {0}: {1}. Node {2} is neither pending, neither running, it''s {3}. Will try again after 5s",
                                        new Object[]{retryCount, t, slave.getNodeName(), state});
                                retryCount++;
                            }

                            Thread.sleep(5000);
                        }
                    }
                })
                , t.getNumExecutors());
    }


    @Override
    public boolean canProvision(Label label) {
        return !getTemplates(label).isEmpty();
    }

    protected AWSCredentialsProvider createCredentialsProvider() {
        return createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);
    }

    public static String getSlaveTypeTagValue(String slaveType, String templateDescription) {
        return templateDescription != null ? slaveType + "_" + templateDescription : slaveType;
    }

    public static AWSCredentialsProvider createCredentialsProvider(final boolean useInstanceProfileForCredentials, final String credentialsId) {
        if (useInstanceProfileForCredentials) {
            return new InstanceProfileCredentialsProvider(false);
        } else if (StringUtils.isBlank(credentialsId)) {
            return new DefaultAWSCredentialsProviderChain();
        } else {
            AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
            if (credentials != null)
                return new AWSStaticCredentialsProvider(credentials.getCredentials());
        }
        return new DefaultAWSCredentialsProviderChain();
    }

    public static AWSCredentialsProvider createCredentialsProvider(
            final boolean useInstanceProfileForCredentials,
            final String credentialsId,
            final String roleArn,
            final String roleSessionName,
            final String region) {

        AWSCredentialsProvider provider = createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);

        if (StringUtils.isNotEmpty(roleArn) && StringUtils.isNotEmpty(roleSessionName)) {
            return new STSAssumeRoleSessionCredentialsProvider.Builder(roleArn, roleSessionName)
                    .withStsClient(AWSSecurityTokenServiceClientBuilder.standard()
                            .withCredentials(provider)
                            .withRegion(region)
                            .withClientConfiguration(createClientConfiguration(convertHostName(region)))
                            .build())
                    .build();
        }

        return provider;
    }

    @CheckForNull
    private static AmazonWebServicesCredentials getCredentials(@CheckForNull String credentialsId) {
        if (StringUtils.isBlank(credentialsId)) {
            return null;
        }
        return (AmazonWebServicesCredentials) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(AmazonWebServicesCredentials.class, Jenkins.get(),
                        ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
    }

    private AmazonEC2 reconnectToEc2() throws IOException {
        synchronized(this) {
            connection = AmazonEC2Factory.getInstance().connect(createCredentialsProvider(), getEc2EndpointUrl());
            return connection;
        }
    }

    /**
     * Connects to EC2 and returns {@link AmazonEC2}, which can then be used to communicate with EC2.
     */
    public AmazonEC2 connect() throws AmazonClientException {
        try {
            if (connection != null) {
                return connection;
            }
            else {
                return reconnectToEc2();
            }
        } catch (IOException e) {
            throw new AmazonClientException("Failed to retrieve the endpoint", e);
        }
    }

    public static ClientConfiguration createClientConfiguration(final String host) {
        ClientConfiguration config = new ClientConfiguration();
        config.setMaxErrorRetry(16); // Default retry limit (3) is low and often
        // cause problems. Raise it a bit.
        // See: https://issues.jenkins-ci.org/browse/JENKINS-26800
        config.setSignerOverride("AWS4SignerType");
        ProxyConfiguration proxyConfig = Jenkins.get().proxy;
        Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(host);
        if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            config.setProxyHost(address.getHostName());
            config.setProxyPort(address.getPort());
            if (null != proxyConfig.getUserName()) {
                config.setProxyUsername(proxyConfig.getUserName());
                config.setProxyPassword(proxyConfig.getPassword());
            }
        }
        return config;
    }

    /***
     * Returns the DNS endpoint for a AWS service based on region provided
     */
    public static String getAwsPartitionHostForService(String region, String service) {
        String host;
        if (region != null && region.startsWith("cn-")) {
            host = service + "." + region + "." + AWS_CN_URL_HOST;
        } else {
            host = service + "." + region + "." + AWS_URL_HOST;
        }
        return host;
    }

    /***
     * Convert a configured hostname like 'us-east-1' to a FQDN or ip address
     */
    public static String convertHostName(String ec2HostName) {
        if (ec2HostName == null || ec2HostName.length() == 0)
            ec2HostName = DEFAULT_EC2_HOST;
        if (!ec2HostName.contains(".")) {
            ec2HostName = getAwsPartitionHostForService(ec2HostName, "ec2");
        }
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
        AWSCredentialsProvider provider = createCredentialsProvider();
        AWSCredentials credentials = provider.getCredentials();
        long expires = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(60);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(path, credentials.getAWSSecretKey());
        request.setExpiration(new Date(expires));
        AmazonS3 s3 =  AmazonS3ClientBuilder.standard().withCredentials(provider).build();
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

    @CheckForNull
    private static SSHUserPrivateKey getSshCredential(@NonNull String id, ItemGroup context){
        SSHUserPrivateKey credential = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        SSHUserPrivateKey.class, // (1)
                        context,
                        null,
                        Collections.emptyList()),
                CredentialsMatchers.withId(id));

        if (credential == null) {
            LOGGER.log(Level.WARNING, "EC2 Plugin could not find the specified credentials ({0}) in the Jenkins Global Credentials Store, EC2 Plugin for cloud must be manually reconfigured", new String[]{id});
        }

        return credential;
    }

    public static abstract class DescriptorImpl extends Descriptor<Cloud> {

        public InstanceType[] getInstanceTypes() {
            return InstanceType.values();
        }

        @POST
        public FormValidation doCheckUseInstanceProfileForCredentials(@QueryParameter boolean value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER) || !value) {
                return FormValidation.ok();
            }
            try {
                new InstanceProfileCredentialsProvider(false).getCredentials();
                return FormValidation.ok();
            } catch (AmazonClientException e) {
                return FormValidation.error(Messages.EC2Cloud_FailedToObtainCredentialsFromEC2(), e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillSshKeysCredentialsIdItems(@AncestorInPath ItemGroup context, @QueryParameter String sshKeysCredentialsId) {
            AbstractIdCredentialsListBoxModel result = new StandardListBoxModel();
            if (Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                result = result
                        .includeEmptyValue()
                        .includeMatchingAs(Jenkins.getAuthentication(), context, SSHUserPrivateKey.class, Collections.<DomainRequirement>emptyList(), CredentialsMatchers.always())
                        .includeMatchingAs(ACL.SYSTEM, context, SSHUserPrivateKey.class, Collections.<DomainRequirement>emptyList(), CredentialsMatchers.always())
                        .includeCurrentValue(sshKeysCredentialsId);
            }
            return result;
        }

        @RequirePOST
        public FormValidation doCheckSshKeysCredentialsId(@AncestorInPath ItemGroup context, @QueryParameter String value) throws IOException, ServletException {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                // Don't do anything if the user is only reading the configuration
                return FormValidation.ok();
            }
            if (value.isEmpty()){
                return FormValidation.ok("No SSH credentials selected, so SSH key management will be handled dynamically by Jenkins");
            }

            SSHUserPrivateKey sshCredential = getSshCredential(value, context);
            String privateKey = "";
            if (sshCredential != null) {
                privateKey = sshCredential.getPrivateKey();
            } else {
                return FormValidation.error("Failed to find credential \"" + value + "\" in store.");
            }

            boolean hasStart = false, hasEnd = false;
            BufferedReader br = new BufferedReader(new StringReader(privateKey));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----") ||
                        line.equals("-----BEGIN OPENSSH PRIVATE KEY-----"))
                    hasStart = true;
                if (line.equals("-----END RSA PRIVATE KEY-----") ||
                        line.equals("-----END OPENSSH PRIVATE KEY-----"))
                    hasEnd = true;
            }
            if (!hasStart)
                return FormValidation.error("This doesn't look like a private key at all");
            if (!hasEnd)
                return FormValidation
                        .error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            return FormValidation.ok();
        }

        /**
         * Tests the connection settings.
         *
         * Overriding needs to {@code @RequirePOST}
         * @param ec2endpoint
         * @param useInstanceProfileForCredentials
         * @param credentialsId
         * @param sshKeysCredentialsId
         * @param roleArn
         * @param roleSessionName
         * @param region
         * @return the validation result
         * @throws IOException
         * @throws ServletException
         */
        @POST
        protected FormValidation doTestConnection(@AncestorInPath ItemGroup context, URL ec2endpoint, boolean useInstanceProfileForCredentials, String credentialsId, String sshKeysCredentialsId, String roleArn, String roleSessionName, String region)
                throws IOException, ServletException {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            try {
                AWSCredentialsProvider credentialsProvider = createCredentialsProvider(useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
                AmazonEC2 ec2 = AmazonEC2Factory.getInstance().connect(credentialsProvider, ec2endpoint);
                ec2.describeInstances();

                if (!sshKeysCredentialsId.isEmpty()) {
                    LOGGER.fine("Static ssh credential is defined, validating....");
                    SSHUserPrivateKey sshCredential = getSshCredential(sshKeysCredentialsId, context);
                    String privateKey = "";
                    if (sshCredential != null) {
                        privateKey = sshCredential.getPrivateKey();

                        if (privateKey.trim().length() > 0) {
                            // check if this key exists
                            EC2PrivateKey pk = new EC2PrivateKey(privateKey);
                            if (pk.find(ec2) == null)
                                return FormValidation
                                        .error("The EC2 key pair private key isn't registered to this EC2 region (fingerprint is "
                                                + pk.getFingerprint() + ")");
                        }
                    }
                }
            } catch(AmazonClientException e){
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential", e);
                return FormValidation.error(e.getMessage());
            }
            return FormValidation.ok(Messages.EC2Cloud_Success());

        }

        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel();
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM, context, AmazonWebServicesCredentials.class, Collections.emptyList(), CredentialsMatchers.always());
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
            lr.setLoggerName(LOGGER.getName());
            PrintStream printStream = listener.getLogger();
            printStream.print(sf.format(lr));
        }
    }

    @Extension
    public static class EC2ConnectionUpdater extends PeriodicWork {
        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(60);
        }

        @Override
        protected void doRun() throws IOException {
            Jenkins instance = Jenkins.get();
            if (instance.clouds != null) {
                for (Cloud cloud : instance.clouds) {
                    if (cloud instanceof EC2Cloud) {
                        EC2Cloud ec2_cloud = (EC2Cloud) cloud;
                        LOGGER.finer(() -> "Checking EC2 Connection on: " + ec2_cloud.getDisplayName());
                        try {
                            if(ec2_cloud.connection != null) {
                                List<Filter> filters = new ArrayList<>();
                                filters.add(new Filter("tag-key").withValues("bogus-EC2ConnectionKeepalive"));
                                DescribeInstancesRequest dir = new DescribeInstancesRequest().withFilters(filters);
                                ec2_cloud.connection.describeInstances(dir);
                            }
                        } catch (AmazonClientException e) {
                            LOGGER.finer(() -> "Reconnecting to EC2 on: " + ec2_cloud.getDisplayName());
                            ec2_cloud.reconnectToEc2();
                        }
                    }
                }
            }
        }
    }
}
