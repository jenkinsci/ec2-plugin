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
import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Failure;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import hudson.plugins.ec2.util.AmazonEC2Factory;
import hudson.plugins.ec2.util.FIPS140Utils;
import hudson.plugins.ec2.util.KeyPair;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.ServiceEndpointKey;
import software.amazon.awssdk.regions.ServiceMetadata;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.SpotInstanceRequest;
import software.amazon.awssdk.services.ec2.model.SpotInstanceState;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * Hudson's view of EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2Cloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());

    public static final String DEFAULT_EC2_HOST = "us-east-1";

    public static final String EC2_SLAVE_TYPE_SPOT = "spot";

    public static final String EC2_SLAVE_TYPE_DEMAND = "demand";

    public static final String EC2_REQUEST_EXPIRED_ERROR_CODE = "RequestExpired";

    private static final SimpleFormatter sf = new SimpleFormatter();

    // if this system property is defined and its value points to a valid ssh private key on disk
    // then this will be used instead of any configured ssh credential
    public static final String SSH_PRIVATE_KEY_FILEPATH = EC2Cloud.class.getName() + ".sshPrivateKeyFilePath";

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

    @CheckForNull
    private String sshKeysCredentialsId;

    /**
     * Upper bound on how many instances we may provision.
     */
    private final int instanceCap;

    private List<? extends SlaveTemplate> templates;

    private transient KeyPair usableKeyPair;

    /**
     * Represents the region. Can be null for backward compatibility reasons.
     */
    private String region;

    private String altEC2Endpoint;

    private boolean noDelayProvisioning;

    private boolean cleanUpOrphanedNodes;

    private transient volatile Ec2Client connection;

    @DataBoundConstructor
    public EC2Cloud(
            String name,
            boolean useInstanceProfileForCredentials,
            String credentialsId,
            String region,
            String privateKey,
            String sshKeysCredentialsId,
            String instanceCapStr,
            List<? extends SlaveTemplate> templates,
            String roleArn,
            String roleSessionName) {
        super(name);
        this.useInstanceProfileForCredentials = useInstanceProfileForCredentials;
        this.roleArn = roleArn;
        this.roleSessionName = roleSessionName;
        this.credentialsId = Util.fixEmpty(credentialsId);
        this.region = Util.fixEmpty(region);
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
    public EC2Cloud(
            String name,
            boolean useInstanceProfileForCredentials,
            String credentialsId,
            String region,
            String privateKey,
            String instanceCapStr,
            List<? extends SlaveTemplate> templates,
            String roleArn,
            String roleSessionName) {
        this(
                name,
                useInstanceProfileForCredentials,
                credentialsId,
                region,
                privateKey,
                null,
                instanceCapStr,
                templates,
                roleArn,
                roleSessionName);
    }

    @Deprecated
    protected EC2Cloud(
            String id,
            boolean useInstanceProfileForCredentials,
            String credentialsId,
            String privateKey,
            String instanceCapStr,
            List<? extends SlaveTemplate> templates,
            String roleArn,
            String roleSessionName) {
        this(
                id,
                useInstanceProfileForCredentials,
                credentialsId,
                privateKey,
                null,
                null,
                instanceCapStr,
                templates,
                roleArn,
                roleSessionName);
    }

    @CheckForNull
    public EC2PrivateKey resolvePrivateKey() {
        if (!System.getProperty(SSH_PRIVATE_KEY_FILEPATH, "").isEmpty()) {
            LOGGER.fine(() -> "(resolvePrivateKey) secret key file configured, will load from disk");
            return EC2PrivateKey.fetchFromDisk();
        } else if (sshKeysCredentialsId != null) {
            LOGGER.fine(() -> "(resolvePrivateKey) Using jenkins ssh credential");
            SSHUserPrivateKey privateKeyCredential = getSshCredential(sshKeysCredentialsId, Jenkins.get());
            if (privateKeyCredential != null) {
                String privateKey = privateKeyCredential.getPrivateKey();
                FIPS140Utils.ensurePrivateKeyInFipsMode(privateKey);
                return new EC2PrivateKey(privateKey);
            }
        }
        return null;
    }

    /**
     * @deprecated Use public field "name" instead.
     */
    @Deprecated
    public String getCloudName() {
        return name;
    }

    public String getRegion() {
        if (region == null) {
            region = DEFAULT_EC2_HOST; // Backward compatibility
        }
        // Handles pre 1.14 region names that used the old AwsRegion enum, note we don't change
        // the region here to keep the meta-data compatible in the case of a downgrade (is that right?)
        if (region.indexOf('_') > 0) {
            return region.replace('_', '-').toLowerCase(Locale.ENGLISH);
        }
        return region;
    }

    @CheckForNull
    @Restricted(NoExternalUse.class)
    public static Region parseRegion(@CheckForNull final String input) {
        final String regionId = Util.fixEmpty(input);
        if (regionId == null) {
            return null;
        }
        return Region.regions().stream()
                .filter(r -> r.id().equals(regionId))
                .findFirst()
                .orElse(null);
    }

    @CheckForNull
    @Restricted(NoExternalUse.class)
    public static URI parseEndpoint(@CheckForNull String input) {
        final String endpoint = Util.fixEmpty(input);
        if (endpoint == null) {
            return null;
        }
        try {
            return new URI(endpoint);
        } catch (URISyntaxException e) {
            LOGGER.log(Level.WARNING, "The alternate EC2 endpoint is malformed ({0}).", endpoint);
            return null;
        }
    }

    @NonNull
    static Region getBootstrapRegion(@CheckForNull URI endpoint) {
        if (endpoint != null) {
            ServiceMetadata metadata = Ec2Client.serviceMetadata();
            for (Region region : metadata.regions()) {
                ServiceEndpointKey key =
                        ServiceEndpointKey.builder().region(region).build();
                if (endpoint.getHost() != null
                        && endpoint.getHost().equals(metadata.endpointFor(key).toString())) {
                    return region;
                }
            }
        }
        return Region.US_EAST_1;
    }

    public boolean isNoDelayProvisioning() {
        return noDelayProvisioning;
    }

    @DataBoundSetter
    public void setNoDelayProvisioning(boolean noDelayProvisioning) {
        this.noDelayProvisioning = noDelayProvisioning;
    }

    public boolean isCleanUpOrphanedNodes() {
        return cleanUpOrphanedNodes;
    }

    @DataBoundSetter
    public void setCleanUpOrphanedNodes(boolean cleanUpOrphanedNodes) {
        this.cleanUpOrphanedNodes = cleanUpOrphanedNodes;
    }

    public String getAltEC2Endpoint() {
        return altEC2Endpoint;
    }

    @DataBoundSetter
    public void setAltEC2Endpoint(String altEC2Endpoint) {
        this.altEC2Endpoint = altEC2Endpoint;
    }

    public void addTemplate(SlaveTemplate newTemplate) throws Exception {
        String newTemplateDescription = newTemplate.description;
        if (getTemplate(newTemplateDescription) != null) {
            throw new Exception(
                    String.format("A SlaveTemplate with description %s already exists", newTemplateDescription));
        }
        List<SlaveTemplate> templatesHolder = new ArrayList<>(templates);
        templatesHolder.add(newTemplate);
        templates = templatesHolder;
    }

    public void updateTemplate(SlaveTemplate newTemplate, String oldTemplateDescription) throws Exception {
        Optional<? extends SlaveTemplate> optionalOldTemplate = templates.stream()
                .filter(template -> Objects.equals(template.description, oldTemplateDescription))
                .findFirst();
        if (optionalOldTemplate.isEmpty()) {
            throw new Exception(
                    String.format("A SlaveTemplate with description %s does not exist", oldTemplateDescription));
        }
        int oldTemplateIndex = templates.indexOf(optionalOldTemplate.get());
        List<SlaveTemplate> templatesHolder = new ArrayList<>(templates);
        templatesHolder.set(oldTemplateIndex, newTemplate);
        templates = templatesHolder;
    }

    private void migratePrivateSshKeyToCredential(String privateKey) {
        // GET matching private key credential from Credential API if exists
        Optional<SSHUserPrivateKey> keyCredential = SystemCredentialsProvider.getInstance().getCredentials().stream()
                .filter(SSHUserPrivateKey.class::isInstance)
                .filter(cred ->
                        ((SSHUserPrivateKey) cred).getPrivateKey().trim().equals(privateKey.trim()))
                .map(cred -> (SSHUserPrivateKey) cred)
                .findFirst();

        if (keyCredential.isPresent()) {
            // SET this.sshKeysCredentialsId with the found credential
            sshKeysCredentialsId = keyCredential.get().getId();
        } else {
            // CREATE new credential
            String credsId = UUID.randomUUID().toString();

            SSHUserPrivateKey sshKeyCredentials = new BasicSSHUserPrivateKey(
                    CredentialsScope.SYSTEM,
                    credsId,
                    "key",
                    new BasicSSHUserPrivateKey.PrivateKeySource() {
                        @NonNull
                        @Override
                        public List<String> getPrivateKeys() {
                            return Collections.singletonList(privateKey.trim());
                        }
                    },
                    "",
                    "EC2 Cloud Private Key - " + getDisplayName());

            addNewGlobalCredential(sshKeyCredentials);

            sshKeysCredentialsId = credsId;
        }
    }

    protected Object readResolve() {
        this.slaveCountingLock = new ReentrantLock();

        for (SlaveTemplate t : templates) {
            t.parent = this;
        }

        if (this.sshKeysCredentialsId == null && this.privateKey != null) {
            String privateKey = this.privateKey.getPrivateKey();
            FIPS140Utils.ensurePrivateKeyInFipsMode(privateKey);
            migratePrivateSshKeyToCredential(privateKey);
        }
        this.privateKey =
                null; // This enforces it not to be persisted and that CasC will never output privateKey on export

        if (this.accessId != null && this.secretKey != null && credentialsId == null) {
            String secretKeyEncryptedValue = this.secretKey.getEncryptedValue();
            // REPLACE this.accessId and this.secretId by a credential

            SystemCredentialsProvider systemCredentialsProvider = SystemCredentialsProvider.getInstance();

            // ITERATE ON EXISTING CREDS AND DON'T CREATE IF EXIST
            for (Credentials credentials : systemCredentialsProvider.getCredentials()) {
                if (credentials instanceof AmazonWebServicesCredentials awsCreds) {
                    AwsCredentials awsCredentials = awsCreds.resolveCredentials();
                    if (accessId.equals(awsCredentials.accessKeyId())
                            && Secret.toString(this.secretKey).equals(awsCredentials.secretAccessKey())) {

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
                    CredentialsScope.SYSTEM,
                    credsId,
                    this.accessId,
                    secretKeyEncryptedValue,
                    "EC2 Cloud - " + getDisplayName()));

            this.credentialsId = credsId;
            this.accessId = null;
            this.secretKey = null;

            // PROBLEM, GLOBAL STORE NOT FOUND
            LOGGER.log(
                    Level.WARNING,
                    "EC2 Plugin could not migrate credentials to the Jenkins Global Credentials Store, EC2 Plugin for cloud {0} must be manually reconfigured",
                    getDisplayName());
        }

        return this;
    }

    private void addNewGlobalCredential(Credentials credentials) {
        for (CredentialsStore credentialsStore : CredentialsProvider.lookupStores(Jenkins.get())) {

            if (credentialsStore instanceof SystemCredentialsProvider.StoreImpl) {

                try {
                    credentialsStore.addCredentials(Domain.global(), credentials);
                } catch (IOException e) {
                    this.credentialsId = null;
                    LOGGER.log(
                            Level.WARNING, "Exception converting legacy configuration to the new credentials API", e);
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
        if (instanceCap == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(instanceCap);
        }
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
    public synchronized KeyPair getKeyPair() throws SdkException, IOException {
        if (usableKeyPair == null) {
            EC2PrivateKey ec2PrivateKey = this.resolvePrivateKey();
            if (ec2PrivateKey != null) {
                usableKeyPair = ec2PrivateKey.find(connect());
            }
        }
        return usableKeyPair;
    }

    /**
     * Debug command to attach to a running instance.
     */
    @RequirePOST
    public void doAttach(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String id)
            throws ServletException, IOException, SdkException {
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
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "The 'template' query parameter is missing");
        }
        SlaveTemplate t = getTemplate(template);
        if (t == null) {
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "No such template: " + template);
        }

        final Jenkins jenkinsInstance = Jenkins.get();
        if (jenkinsInstance.isQuietingDown()) {
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "Jenkins instance is quieting down");
        }
        if (jenkinsInstance.isTerminating()) {
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "Jenkins instance is terminating");
        }
        try {
            List<EC2AbstractSlave> nodes = getNewOrExistingAvailableSlave(t, 1, true);
            if (nodes == null || nodes.isEmpty()) {
                throw HttpResponses.error(
                        HttpServletResponse.SC_BAD_REQUEST,
                        "Cloud or AMI instance cap would be exceeded for: " + template);
            }

            // Reconnect a stopped instance, the ADD is invoking the connect only for the node creation
            Computer c = nodes.get(0).toComputer();
            if (nodes.get(0).getStopOnTerminate() && c != null) {
                c.connect(false);
            }
            jenkinsInstance.addNode(nodes.get(0));

            return HttpResponses.redirectViaContextPath(
                    "/computer/" + nodes.get(0).getNodeName());
        } catch (SdkException e) {
            throw HttpResponses.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * Counts the number of instances in EC2 that can be used with the specified image and a template. Also removes any
     * nodes associated with canceled requests.
     *
     * @param template If left null, then all instances are counted.
     */
    private int countCurrentEC2Slaves(SlaveTemplate template) throws SdkException {
        String jenkinsServerUrl = JenkinsLocationConfiguration.get().getUrl();

        if (jenkinsServerUrl == null) {
            LOGGER.log(
                    Level.WARNING,
                    "No Jenkins server URL specified, it is strongly recommended to open /configure and set the server URL. "
                            + "Not having has disabled the per-controller instance cap counting (cf. https://github.com/jenkinsci/ec2-plugin/pull/310)");
        }

        LOGGER.log(
                Level.FINE,
                "Counting current agents: "
                        + (template != null
                                ? (" AMI: " + template.getAmi() + " TemplateDesc: " + template.description)
                                : " All AMIS")
                        + " Jenkins Server: " + jenkinsServerUrl);
        int n = 0;
        Set<String> instanceIds = new HashSet<>();
        String description = template != null ? template.description : null;

        List<Filter> filters = getGenericFilters(jenkinsServerUrl, template);
        filters.add(Filter.builder()
                .name("instance-state-name")
                .values("running", "pending", "stopping")
                .build());
        DescribeInstancesRequest dir =
                DescribeInstancesRequest.builder().filters(filters).build();
        DescribeInstancesResponse result = null;
        do {
            result = connect().describeInstances(dir);
            dir = DescribeInstancesRequest.builder()
                    .filters(filters)
                    .nextToken(result.nextToken())
                    .build();
            for (Reservation r : result.reservations()) {
                for (Instance i : r.instances()) {
                    if (isEc2ProvisionedAmiSlave(i.tags(), description)) {
                        LOGGER.log(
                                Level.FINE,
                                "Existing instance found: " + i.instanceId() + " AMI: " + i.imageId()
                                        + (template != null ? (" Template: " + description) : "") + " Jenkins Server: "
                                        + jenkinsServerUrl);
                        n++;
                        instanceIds.add(i.instanceId());
                    }
                }
            }
        } while (result.nextToken() != null);

        n += countCurrentEC2SpotSlaves(template, jenkinsServerUrl, instanceIds);

        return n;
    }

    /**
     * Counts the number of EC2 Spot instances that can be used with the specified image and a template. Also removes any
     * nodes associated with canceled requests.
     *
     * @param template If left null, then all spot instances are counted.
     */
    private int countCurrentEC2SpotSlaves(SlaveTemplate template, String jenkinsServerUrl, Set<String> instanceIds)
            throws SdkException {
        int n = 0;
        String description = template != null ? template.description : null;
        List<SpotInstanceRequest> sirs = null;
        List<Filter> filters = getGenericFilters(jenkinsServerUrl, template);
        if (template != null) {
            filters.add(Filter.builder()
                    .name("launch.image-id")
                    .values(template.getAmi())
                    .build());
        }

        DescribeSpotInstanceRequestsRequest dsir = DescribeSpotInstanceRequestsRequest.builder()
                .filters(filters)
                .maxResults(100)
                .build();
        Set<SpotInstanceRequest> sirSet = new HashSet<>();
        DescribeSpotInstanceRequestsResponse sirResp = null;

        do {
            sirResp = connect().describeSpotInstanceRequests(dsir);
            sirs = sirResp.spotInstanceRequests();
            dsir = DescribeSpotInstanceRequestsRequest.builder()
                    .filters(filters)
                    .maxResults(100)
                    .nextToken(sirResp.nextToken())
                    .build();

            if (sirs != null) {
                for (SpotInstanceRequest sir : sirs) {
                    sirSet.add(sir);
                    if (sir.state() == SpotInstanceState.OPEN || sir.state() == SpotInstanceState.ACTIVE) {
                        if (sir.instanceId() != null && instanceIds.contains(sir.instanceId())) {
                            continue;
                        }

                        if (isEc2ProvisionedAmiSlave(sir.tags(), description)) {
                            LOGGER.log(
                                    Level.FINE,
                                    "Spot instance request found: " + sir.spotInstanceRequestId() + " AMI: "
                                            + sir.instanceId() + " state: " + sir.state() + " status: "
                                            + sir.status());

                            n++;
                            if (sir.instanceId() != null) {
                                instanceIds.add(sir.instanceId());
                            }
                        }
                    } else {
                        // Cancelled or otherwise dead
                        for (Node node : Jenkins.get().getNodes()) {
                            try {
                                if (!(node instanceof EC2SpotSlave ec2Slave)) {
                                    continue;
                                }
                                if (ec2Slave.getSpotInstanceRequestId().equals(sir.spotInstanceRequestId())) {
                                    LOGGER.log(
                                            Level.INFO,
                                            "Removing dead request: " + sir.spotInstanceRequestId() + " AMI: "
                                                    + sir.instanceId() + " state: " + sir.state() + " status: "
                                                    + sir.status());
                                    Jenkins.get().removeNode(node);
                                    break;
                                }
                            } catch (IOException e) {
                                LOGGER.log(
                                        Level.WARNING,
                                        "Failed to remove node for dead request: " + sir.spotInstanceRequestId()
                                                + " AMI: " + sir.instanceId() + " state: " + sir.state()
                                                + " status: " + sir.status(),
                                        e);
                            }
                        }
                    }
                }
            }
        } while (sirResp.nextToken() != null);
        n += countJenkinsNodeSpotInstancesWithoutRequests(template, sirSet, instanceIds);
        return n;
    }

    // Count nodes where the spot request does not yet exist (sometimes it takes time for the request to appear
    // in the EC2 API)
    private int countJenkinsNodeSpotInstancesWithoutRequests(
            SlaveTemplate template, Set<SpotInstanceRequest> sirSet, Set<String> instanceIds) throws SdkException {
        int n = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (!(node instanceof EC2SpotSlave ec2Slave)) {
                continue;
            }
            SpotInstanceRequest sir = ec2Slave.getSpotRequest();

            if (sir == null) {
                LOGGER.log(Level.FINE, "Found spot node without request: " + ec2Slave.getSpotInstanceRequestId());
                n++;
                continue;
            }

            if (sirSet.contains(sir)) {
                continue;
            }

            sirSet.add(sir);

            if (sir.state() == SpotInstanceState.OPEN || sir.state() == SpotInstanceState.ACTIVE) {
                if (template != null) {
                    List<Tag> instanceTags = sir.tags();
                    for (Tag tag : instanceTags) {
                        if (StringUtils.equals(tag.key(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)
                                && StringUtils.equals(
                                        tag.value(), getSlaveTypeTagValue(EC2_SLAVE_TYPE_SPOT, template.description))
                                && sir.launchSpecification().imageId().equals(template.getAmi())) {

                            if (sir.instanceId() != null && instanceIds.contains(sir.instanceId())) {
                                continue;
                            }

                            LOGGER.log(
                                    Level.FINE,
                                    "Spot instance request found (from node): " + sir.spotInstanceRequestId()
                                            + " AMI: " + sir.instanceId() + " state: " + sir.state() + " status: "
                                            + sir.status());
                            n++;

                            if (sir.instanceId() != null) {
                                instanceIds.add(sir.instanceId());
                            }
                        }
                    }
                }
            }
        }
        return n;
    }

    private List<Filter> getGenericFilters(String jenkinsServerUrl, SlaveTemplate template) {
        List<Filter> filters = new ArrayList<>();
        filters.add(Filter.builder()
                .name("tag-key")
                .values(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)
                .build());
        if (jenkinsServerUrl != null) {
            // The instances must match the jenkins server url
            filters.add(Filter.builder()
                    .name("tag:" + EC2Tag.TAG_NAME_JENKINS_SERVER_URL)
                    .values(jenkinsServerUrl)
                    .build());
        } else {
            filters.add(Filter.builder()
                    .name("tag-key")
                    .values(EC2Tag.TAG_NAME_JENKINS_SERVER_URL)
                    .build());
        }

        if (template != null) {
            List<EC2Tag> tags = template.getTags();
            if (tags != null) {
                for (EC2Tag tag : tags) {
                    if (tag.getName() != null && tag.getValue() != null) {
                        filters.add(Filter.builder()
                                .name("tag:" + tag.getName())
                                .values(tag.getValue())
                                .build());
                    }
                }
            }
        }
        return filters;
    }

    private boolean isEc2ProvisionedAmiSlave(List<Tag> tags, String description) {
        for (Tag tag : tags) {
            if (StringUtils.equals(tag.key(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)) {
                if (description == null) {
                    return true;
                } else if (StringUtils.equals(tag.value(), EC2Cloud.EC2_SLAVE_TYPE_DEMAND)
                        || StringUtils.equals(tag.value(), EC2Cloud.EC2_SLAVE_TYPE_SPOT)) {
                    // To handle cases where description is null and also upgrade cases for existing agent nodes.
                    return true;
                } else if (StringUtils.equals(
                                tag.value(), getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_DEMAND, description))
                        || StringUtils.equals(
                                tag.value(), getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_SPOT, description))) {
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
    private int getPossibleNewSlavesCount(SlaveTemplate template) throws SdkException {
        int estimatedTotalSlaves = countCurrentEC2Slaves(null);
        int estimatedAmiSlaves = countCurrentEC2Slaves(template);

        int availableTotalSlaves = instanceCap - estimatedTotalSlaves;
        int availableAmiSlaves = template.getInstanceCap() - estimatedAmiSlaves;
        LOGGER.log(
                Level.FINE,
                "Available Total Agents: " + availableTotalSlaves + " Available AMI agents: " + availableAmiSlaves
                        + " AMI: " + template.getAmi() + " TemplateDesc: " + template.description);

        return Math.min(availableAmiSlaves, availableTotalSlaves);
    }

    /**
     * Obtains a agent whose AMI matches the AMI of the given template, and that also has requiredLabel (if requiredLabel is non-null)
     * forceCreateNew specifies that the creation of a new agent is required. Otherwise, an existing matching agent may be re-used
     */
    private List<EC2AbstractSlave> getNewOrExistingAvailableSlave(SlaveTemplate t, int number, boolean forceCreateNew)
            throws IOException {
        try {
            slaveCountingLock.lock();
            int possibleSlavesCount = getPossibleNewSlavesCount(t);
            if (possibleSlavesCount <= 0) {
                LOGGER.log(Level.INFO, "{0}. Cannot provision - no capacity for instances: " + possibleSlavesCount, t);
                return null;
            }

            EnumSet<SlaveTemplate.ProvisionOptions> provisionOptions;
            if (forceCreateNew) {
                provisionOptions = EnumSet.of(SlaveTemplate.ProvisionOptions.FORCE_CREATE);
            } else {
                provisionOptions = EnumSet.of(SlaveTemplate.ProvisionOptions.ALLOW_CREATE);
            }

            if (number > possibleSlavesCount) {
                LOGGER.log(
                        Level.INFO,
                        String.format(
                                "%d nodes were requested for the template %s, "
                                        + "but because of instance cap only %d can be provisioned",
                                number, t, possibleSlavesCount));
                number = possibleSlavesCount;
            }

            return t.provision(number, provisionOptions);
        } finally {
            slaveCountingLock.unlock();
        }
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
                LOGGER.log(
                        Level.INFO,
                        "{0}. Attempting to provision agent needed by excess workload of " + excessWorkload + " units",
                        t);
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
                if (excessWorkload == 0) {
                    break;
                }
            } catch (AwsServiceException e) {
                LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
                if ("RequestExpired".equals(e.awsErrorDetails().errorCode())
                        || "ExpiredToken".equals(e.awsErrorDetails().errorCode())) {
                    // A RequestExpired or ExpiredToken error can indicate that credentials have expired so reconnect
                    LOGGER.log(Level.INFO, "Reconnecting to EC2 due to RequestExpired or ExpiredToken error");
                    try {
                        reconnectToEc2();
                    } catch (IOException e2) {
                        LOGGER.log(Level.WARNING, "Failed to reconnect ec2", e2);
                    }
                }
            } catch (SdkException | IOException e) {
                LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
            }
        }
        LOGGER.log(Level.INFO, "We have now {0} computers, waiting for {1} more", new Object[] {
            jenkinsInstance.getComputers().length, plannedNodes.size()
        });
        return plannedNodes;
    }

    private static void attachSlavesToJenkins(Jenkins jenkins, List<EC2AbstractSlave> slaves, SlaveTemplate t)
            throws IOException {
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
            LOGGER.log(Level.INFO, "{0}. Attempting to provision {1} agent(s)", new Object[] {t, number});
            final List<EC2AbstractSlave> slaves = getNewOrExistingAvailableSlave(t, number, false);

            if (slaves == null || slaves.isEmpty()) {
                LOGGER.warning("Can't raise nodes for " + t);
                return;
            }

            attachSlavesToJenkins(jenkinsInstance, slaves, t);

            LOGGER.log(Level.INFO, "{0}. Attempting provision finished", t);
            LOGGER.log(Level.INFO, "We have now {0} computers, waiting for {1} more", new Object[] {
                Jenkins.get().getComputers().length, number
            });
        } catch (SdkException | IOException e) {
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
    void attemptReattachOrphanOrStoppedNodes(Jenkins jenkinsInstance, SlaveTemplate template, int requestedNum)
            throws IOException {
        LOGGER.info("Attempting to wake & re-attach orphan/stopped nodes");
        Ec2Client ec2 = this.connect();
        DescribeInstancesResponse diResult = template.getDescribeInstanceResult(ec2, true);
        List<Instance> orphansOrStopped = template.findOrphansOrStopped(diResult, requestedNum);
        template.wakeOrphansOrStoppedUp(ec2, orphansOrStopped);
        /* If the number of possible nodes to re-attach is greater than the number of nodes requested, will only attempt to re-attach up to the number requested */
        while (orphansOrStopped.size() > requestedNum) {
            orphansOrStopped.remove(0);
        }
        attachSlavesToJenkins(jenkinsInstance, template.toSlaves(orphansOrStopped), template);
        if (!orphansOrStopped.isEmpty()) {
            LOGGER.info("Found and re-attached " + orphansOrStopped.size() + " orphan/stopped nodes");
        }
    }

    private PlannedNode createPlannedNode(final SlaveTemplate t, final EC2AbstractSlave slave) {
        return new PlannedNode(
                t.getDisplayName(),
                Computer.threadPoolForRemoting.submit(new Callable<>() {
                    int retryCount = 0;
                    private static final int DESCRIBE_LIMIT = 2;

                    @Override
                    public Node call() throws Exception {
                        while (true) {
                            String instanceId = slave.getInstanceId();
                            if (slave instanceof EC2SpotSlave) {
                                if (((EC2SpotSlave) slave).isSpotRequestDead()) {
                                    LOGGER.log(
                                            Level.WARNING,
                                            "{0} Spot request died, can't do anything. Terminate provisioning",
                                            t);
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
                                LOGGER.log(
                                        Level.WARNING,
                                        "{0} Can't find instance with instance id `{1}` in cloud {2}. Terminate provisioning ",
                                        new Object[] {t, instanceId, slave.cloudName});
                                return null;
                            }

                            InstanceStateName state = instance.state().name();
                            if (state.equals(InstanceStateName.RUNNING)) {
                                // Spot instance are not reconnected automatically,
                                // but could be new orphans that has the option enable
                                Computer c = slave.toComputer();
                                if (slave.getStopOnTerminate() && (c != null)) {
                                    c.connect(false);
                                }

                                long secondsSinceStart = Instant.now().until(instance.launchTime(), ChronoUnit.SECONDS);
                                LOGGER.log(
                                        Level.INFO,
                                        "{0} Node {1} moved to RUNNING state in {2} seconds and is ready to be connected by Jenkins",
                                        new Object[] {t, slave.getNodeName(), secondsSinceStart});
                                return slave;
                            }

                            if (!state.equals(InstanceStateName.PENDING)) {

                                if (retryCount >= DESCRIBE_LIMIT) {
                                    LOGGER.log(
                                            Level.WARNING,
                                            "Instance {0} did not move to running after {1} attempts, terminating provisioning",
                                            new Object[] {instanceId, retryCount});
                                    return null;
                                }

                                LOGGER.log(
                                        Level.INFO,
                                        "Attempt {0}: {1}. Node {2} is neither pending, neither running, it''s {3}. Will try again after 5s",
                                        new Object[] {retryCount, t, slave.getNodeName(), state});
                                retryCount++;
                            }

                            Thread.sleep(5000);
                        }
                    }
                }),
                t.getNumExecutors());
    }

    @Override
    public boolean canProvision(Label label) {
        return !getTemplates(label).isEmpty();
    }

    protected AwsCredentialsProvider createCredentialsProvider() {
        return createCredentialsProvider(
                isUseInstanceProfileForCredentials(),
                getCredentialsId(),
                getRoleArn(),
                getRoleSessionName(),
                getRegion());
    }

    public static String getSlaveTypeTagValue(String slaveType, String templateDescription) {
        return templateDescription != null ? slaveType + "_" + templateDescription : slaveType;
    }

    public static AwsCredentialsProvider createCredentialsProvider(
            final boolean useInstanceProfileForCredentials, final String credentialsId) {
        if (useInstanceProfileForCredentials) {
            return InstanceProfileCredentialsProvider.create();
        } else if (StringUtils.isBlank(credentialsId)) {
            return DefaultCredentialsProvider.builder().build();
        } else {
            AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
            if (credentials != null) {
                return StaticCredentialsProvider.create(credentials.resolveCredentials());
            }
        }
        return DefaultCredentialsProvider.builder().build();
    }

    public static AwsCredentialsProvider createCredentialsProvider(
            final boolean useInstanceProfileForCredentials,
            final String credentialsId,
            final String roleArn,
            final String roleSessionName,
            final String region) {

        AwsCredentialsProvider provider = createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);

        if (StringUtils.isNotEmpty(roleArn)) {
            AssumeRoleRequest assumeRoleRequest = AssumeRoleRequest.builder()
                    .roleArn(roleArn)
                    .roleSessionName(StringUtils.defaultIfBlank(roleSessionName, "Jenkins"))
                    .build();

            StsClientBuilder stsClientBuilder = StsClient.builder()
                    .credentialsProvider(provider)
                    .httpClient(getHttpClient())
                    .overrideConfiguration(createClientOverrideConfiguration());
            Region parsed = parseRegion(region);
            if (parsed != null) {
                stsClientBuilder.region(parsed);
            }
            StsClient stsClient = stsClientBuilder.build();

            return StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .refreshRequest(assumeRoleRequest)
                    .build();
        }

        return provider;
    }

    @CheckForNull
    private static AmazonWebServicesCredentials getCredentials(@CheckForNull String credentialsId) {
        if (StringUtils.isBlank(credentialsId)) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        AmazonWebServicesCredentials.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
    }

    private Ec2Client reconnectToEc2() throws IOException {
        synchronized (this) {
            connection = AmazonEC2Factory.getInstance()
                    .connect(createCredentialsProvider(), parseRegion(getRegion()), parseEndpoint(getAltEC2Endpoint()));
            return connection;
        }
    }

    /**
     * Connects to EC2 and returns {@link Ec2Client}, which can then be used to communicate with EC2.
     */
    public Ec2Client connect() throws SdkException {
        try {
            if (connection != null) {
                return connection;
            } else {
                return reconnectToEc2();
            }
        } catch (IOException e) {
            throw SdkException.create("Failed to retrieve the endpoint", e);
        }
    }

    public static SdkHttpClient getHttpClient() {
        Jenkins instance = Jenkins.getInstanceOrNull();

        ProxyConfiguration proxy = instance != null ? instance.proxy : null;
        ApacheHttpClient.Builder builder = ApacheHttpClient.builder();
        if (proxy != null && proxy.name != null && !proxy.name.isEmpty()) {
            software.amazon.awssdk.http.apache.ProxyConfiguration.Builder proxyConfiguration =
                    software.amazon.awssdk.http.apache.ProxyConfiguration.builder()
                            .endpoint(URI.create(String.format("http://%s:%s", proxy.name, proxy.port)));
            if (proxy.getUserName() != null) {
                proxyConfiguration.username(proxy.getUserName());
                proxyConfiguration.password(Secret.toString(proxy.getSecretPassword()));
            }
            builder.proxyConfiguration(proxyConfiguration.build());
        }
        return builder.build();
    }

    public static ClientOverrideConfiguration createClientOverrideConfiguration() {
        // Default retry limit (3) is low and often cause problems. Raise it a bit.
        // See: https://issues.jenkins-ci.org/browse/JENKINS-26800
        ClientOverrideConfiguration config = ClientOverrideConfiguration.builder()
                .putAdvancedOption(SdkAdvancedClientOption.SIGNER, Aws4Signer.create())
                .retryPolicy(RetryPolicy.builder().numRetries(16).build())
                .build();
        return config;
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
    private static SSHUserPrivateKey getSshCredential(String id, ItemGroup context) {

        SSHUserPrivateKey credential = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        SSHUserPrivateKey.class, // (1)
                        context,
                        null,
                        Collections.emptyList()),
                CredentialsMatchers.withId(id));

        if (credential == null) {
            LOGGER.log(
                    Level.WARNING,
                    "EC2 Plugin could not find the specified credentials ({0}) in the Jenkins Global Credentials Store, EC2 Plugin for cloud must be manually reconfigured",
                    new String[] {id});
        }

        return credential;
    }

    @Extension
    @Symbol("amazonEC2")
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "Amazon EC2";
        }

        public InstanceType[] getInstanceTypes() {
            return InstanceType.values();
        }

        @POST
        public FormValidation doCheckUseInstanceProfileForCredentials(@QueryParameter boolean value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER) || !value) {
                return FormValidation.ok();
            }
            try {
                InstanceProfileCredentialsProvider.builder().build().resolveCredentials();
                return FormValidation.ok();
            } catch (SdkException e) {
                return FormValidation.error(Messages.EC2Cloud_FailedToObtainCredentialsFromEC2(), e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillSshKeysCredentialsIdItems(
                @AncestorInPath ItemGroup context, @QueryParameter String sshKeysCredentialsId) {
            AbstractIdCredentialsListBoxModel result = new StandardListBoxModel();
            if (Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                result = result.includeEmptyValue()
                        .includeMatchingAs(
                                Jenkins.getAuthentication2(),
                                context,
                                SSHUserPrivateKey.class,
                                Collections.emptyList(),
                                CredentialsMatchers.always())
                        .includeMatchingAs(
                                ACL.SYSTEM2,
                                context,
                                SSHUserPrivateKey.class,
                                Collections.emptyList(),
                                CredentialsMatchers.always())
                        .includeCurrentValue(sshKeysCredentialsId);
            }
            return result;
        }

        @NonNull
        @RequirePOST
        @Restricted(NoExternalUse.class)
        @VisibleForTesting
        public FormValidation doCheckRoleSessionName(
                @QueryParameter String roleArn, @QueryParameter String roleSessionName) {
            // Don't do anything if the user is only reading the configuration
            if (Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                if (StringUtils.isNotEmpty(roleArn) && StringUtils.isBlank(roleSessionName)) {
                    return FormValidation.warning(
                            "Session Name is recommended when specifying an Arn Role. If empty, 'Jenkins' will be used.");
                }
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckSshKeysCredentialsId(
                @AncestorInPath ItemGroup context, @QueryParameter String value) throws IOException, ServletException {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                // Don't do anything if the user is only reading the configuration
                return FormValidation.ok();
            }

            String privateKey;
            List<FormValidation> validations = new ArrayList<>();

            if (System.getProperty(SSH_PRIVATE_KEY_FILEPATH, "").isEmpty()) {
                // not using a static ssh key file
                if (value == null || value.isEmpty()) {
                    return FormValidation.error("No ssh credentials selected and no private key file defined");
                }

                SSHUserPrivateKey sshCredential = getSshCredential(value, context);
                if (sshCredential != null) {
                    privateKey = sshCredential.getPrivateKey();
                } else {
                    return FormValidation.error("Failed to find credential \"" + value + "\" in store.");
                }
            } else {
                EC2PrivateKey k = EC2PrivateKey.fetchFromDisk();
                if (k == null) {
                    validations.add(FormValidation.error(
                            "Failed to find private key file " + System.getProperty(SSH_PRIVATE_KEY_FILEPATH)));
                    if (!StringUtils.isEmpty(value)) {
                        validations.add(FormValidation.warning(
                                "Private key file path defined, selected credential will be ignored"));
                    }
                    return FormValidation.aggregate(validations);
                }
                privateKey = k.getPrivateKey();
            }

            boolean hasStart = false, hasEnd = false;
            BufferedReader br = new BufferedReader(new StringReader(privateKey));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----")
                        || line.equals("-----BEGIN OPENSSH PRIVATE KEY-----")) {
                    hasStart = true;
                }
                if (line.equals("-----END RSA PRIVATE KEY-----") || line.equals("-----END OPENSSH PRIVATE KEY-----")) {
                    hasEnd = true;
                }
            }
            if (!hasStart) {
                validations.add(FormValidation.error("This doesn't look like a private key at all"));
            }
            if (!hasEnd) {
                validations.add(FormValidation.error(
                        "The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?"));
            }

            if (!System.getProperty(SSH_PRIVATE_KEY_FILEPATH, "").isEmpty()) {
                if (!StringUtils.isEmpty(value)) {
                    validations.add(FormValidation.warning("Using private key file instead of selected credential"));
                } else {
                    validations.add(FormValidation.ok("Using private key file"));
                }
            }

            try {
                FIPS140Utils.ensurePrivateKeyInFipsMode(privateKey);
            } catch (IllegalArgumentException ex) {
                validations.add(FormValidation.error(ex, ex.getLocalizedMessage()));
            }

            validations.add(FormValidation.ok("SSH key validation successful"));
            return FormValidation.aggregate(validations);
        }

        /**
         * Tests the connection settings.
         * <p>
         * Overriding needs to {@code @RequirePOST}
         * @param region
         * @param useInstanceProfileForCredentials
         * @param credentialsId
         * @param sshKeysCredentialsId
         * @param roleArn
         * @param roleSessionName
         * @return the validation result
         * @throws IOException
         * @throws ServletException
         */
        @RequirePOST
        public FormValidation doTestConnection(
                @AncestorInPath ItemGroup context,
                @QueryParameter String region,
                @QueryParameter String altEC2Endpoint,
                @QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId,
                @QueryParameter String sshKeysCredentialsId,
                @QueryParameter String roleArn,
                @QueryParameter String roleSessionName)
                throws IOException, ServletException {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            try {
                List<FormValidation> validations = new ArrayList<>();

                LOGGER.fine(() -> "begin doTestConnection()");
                String privateKey = "";
                if (System.getProperty(SSH_PRIVATE_KEY_FILEPATH, "").isEmpty()) {
                    LOGGER.fine(() -> "static credential is in use");
                    SSHUserPrivateKey sshCredential = getSshCredential(sshKeysCredentialsId, context);
                    if (sshCredential != null) {
                        privateKey = sshCredential.getPrivateKey();
                    } else {
                        return FormValidation.error(
                                "Failed to find credential \"" + sshKeysCredentialsId + "\" in store.");
                    }
                } else {
                    EC2PrivateKey k = EC2PrivateKey.fetchFromDisk();
                    if (k == null) {
                        validations.add(FormValidation.error(
                                "Failed to find private key file " + System.getProperty(SSH_PRIVATE_KEY_FILEPATH)));
                        if (!StringUtils.isEmpty(sshKeysCredentialsId)) {
                            validations.add(FormValidation.warning(
                                    "Private key file path defined, selected credential will be ignored"));
                        }
                        return FormValidation.aggregate(validations);
                    }
                    privateKey = k.getPrivateKey();
                }
                LOGGER.fine(() -> "private key found ok");

                if (Util.fixEmpty(region) == null) {
                    region = DEFAULT_EC2_HOST;
                }

                AwsCredentialsProvider credentialsProvider = createCredentialsProvider(
                        useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
                Ec2Client ec2 = AmazonEC2Factory.getInstance()
                        .connect(credentialsProvider, parseRegion(region), parseEndpoint(altEC2Endpoint));
                ec2.describeInstances();

                if (!privateKey.trim().isEmpty()) {
                    // check if this key exists
                    EC2PrivateKey pk = new EC2PrivateKey(privateKey);
                    if (pk.find(ec2) == null) {
                        validations.add(FormValidation.error(
                                "The EC2 key pair private key isn't registered to this EC2 region (fingerprint is "
                                        + pk.getFingerprint() + ")"));
                    }
                }

                if (!System.getProperty(SSH_PRIVATE_KEY_FILEPATH, "").isEmpty()) {
                    if (!StringUtils.isEmpty(sshKeysCredentialsId)) {
                        validations.add(
                                FormValidation.warning("Using private key file instead of selected credential"));
                    } else {
                        validations.add(FormValidation.ok("Using private key file"));
                    }
                }

                try {
                    FIPS140Utils.ensurePrivateKeyInFipsMode(privateKey);
                } catch (IllegalArgumentException ex) {
                    validations.add(FormValidation.error(ex, ex.getLocalizedMessage()));
                }

                validations.add(FormValidation.ok(Messages.EC2Cloud_Success()));
                return FormValidation.aggregate(validations);
            } catch (SdkException e) {
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential", e);
                return FormValidation.error(e.getMessage());
            }
        }

        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel();
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            context,
                            AmazonWebServicesCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always());
        }

        @POST
        public FormValidation doCheckCloudName(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            try {
                Jenkins.checkGoodName(value);
            } catch (Failure e) {
                return FormValidation.error(e.getMessage());
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckAltEC2Endpoint(@QueryParameter String value) {
            if (Util.fixEmpty(value) != null) {
                try {
                    new URL(value);
                } catch (MalformedURLException ignored) {
                    return FormValidation.error(Messages.EC2Cloud_MalformedUrl());
                }
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public ListBoxModel doFillRegionItems(
                @QueryParameter String altEC2Endpoint,
                @QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId)
                throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            if (Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                try {
                    AwsCredentialsProvider credentialsProvider =
                            createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);
                    URI endpoint = parseEndpoint(altEC2Endpoint);
                    Ec2Client client = AmazonEC2Factory.getInstance()
                            .connect(credentialsProvider, getBootstrapRegion(endpoint), endpoint);
                    DescribeRegionsResponse regions = client.describeRegions();
                    List<software.amazon.awssdk.services.ec2.model.Region> regionList = regions.regions();
                    for (software.amazon.awssdk.services.ec2.model.Region r : regionList) {
                        String name = r.regionName();
                        model.add(name, name);
                    }
                } catch (SdkClientException ex) {
                    // Ignore, as this may happen before the credentials are specified
                }
            }
            return model;
        }
    }

    public static void log(Logger logger, Level level, TaskListener listener, String message) {
        log(logger, level, listener, message, null);
    }

    public static void log(Logger logger, Level level, TaskListener listener, String message, Throwable exception) {
        logger.log(level, message, exception);
        if (listener != null) {
            if (exception != null) {
                message += " Exception: " + exception;
            }
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
                    if (cloud instanceof EC2Cloud ec2_cloud) {
                        LOGGER.finer(() -> "Checking EC2 Connection on: " + ec2_cloud.getDisplayName());
                        try {
                            if (ec2_cloud.connection != null) {
                                List<Filter> filters = new ArrayList<>();
                                filters.add(Filter.builder()
                                        .name("tag-key")
                                        .values("bogus-EC2ConnectionKeepalive")
                                        .build());
                                DescribeInstancesRequest dir = DescribeInstancesRequest.builder()
                                        .filters(filters)
                                        .build();
                                ec2_cloud.connection.describeInstances(dir);
                            }
                        } catch (SdkException e) {
                            LOGGER.finer(() -> "Reconnecting to EC2 on: " + ec2_cloud.getDisplayName());
                            ec2_cloud.reconnectToEc2();
                        }
                    }
                }
            }
        }
    }
}
