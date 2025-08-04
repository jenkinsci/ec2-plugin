/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.plugins.ec2.util.AmazonEC2Factory;
import hudson.plugins.ec2.util.ResettableCountDownLatch;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import java.io.Serial;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AvailabilityZone;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.DeleteTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeAvailabilityZonesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceBlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

/**
 * Agent running on EC2.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("serial")
public abstract class EC2AbstractSlave extends Slave {
    public static final Boolean DEFAULT_METADATA_SUPPORTED = Boolean.TRUE;
    public static final Boolean DEFAULT_METADATA_ENDPOINT_ENABLED = Boolean.TRUE;
    public static final Boolean DEFAULT_METADATA_TOKENS_REQUIRED = Boolean.TRUE;
    public static final Boolean DEFAULT_ENCLAVE_ENABLED = Boolean.FALSE;
    public static final Integer DEFAULT_METADATA_HOPS_LIMIT = 1;
    public static final String DEFAULT_JAVA_PATH = "java";

    private static final Logger LOGGER = Logger.getLogger(EC2AbstractSlave.class.getName());

    protected String instanceId;

    /**
     * Comes from {@link SlaveTemplate#initScript}.
     */
    public final String initScript;

    public final String tmpDir;
    public final String remoteAdmin; // e.g. 'ubuntu'

    public final String templateDescription;

    public final String javaPath;
    public final String jvmopts; // e.g. -Xmx1g
    public final boolean stopOnTerminate;
    public final String idleTerminationMinutes;

    @Deprecated
    public transient boolean useDedicatedTenancy;

    public boolean isConnected = false;
    public List<EC2Tag> tags;
    public final String cloudName;
    public AMITypeData amiType;
    public int maxTotalUses;
    public final Tenancy tenancy;
    private String instanceType;

    private Boolean metadataSupported;
    private Boolean metadataEndpointEnabled;
    private Boolean metadataTokensRequired;
    private Integer metadataHopsLimit;

    private Boolean enclaveEnabled;

    // Temporary stuff that is obtained live from EC2
    public transient String publicDNS;
    public transient String privateDNS;

    /* The last instance data to be fetched for the agent */
    protected transient Instance lastFetchInstance = null;

    /* The time at which we fetched the last instance data */
    protected transient long lastFetchTime;

    /** Terminate was scheduled */
    protected transient ResettableCountDownLatch terminateScheduled = new ResettableCountDownLatch(1, false);

    /*
     * The time (in milliseconds) after which we will always re-fetch externally changeable EC2 data when we are asked
     * for it
     */
    protected static final long MIN_FETCH_TIME =
            Long.getLong("hudson.plugins.ec2.EC2AbstractSlave.MIN_FETCH_TIME", TimeUnit.SECONDS.toMillis(20));

    protected final int launchTimeout;

    // Deprecated by the AMITypeData data structure
    @Deprecated
    protected transient int sshPort;

    @Deprecated
    public transient String rootCommandPrefix; // e.g. 'sudo'

    @Deprecated
    public transient boolean usePrivateDnsName;

    public transient String slaveCommandPrefix;

    public transient String slaveCommandSuffix;

    private transient Instant createdTime;

    public static final String TEST_ZONE = "testZone";

    public EC2AbstractSlave(
            String name,
            String instanceId,
            String templateDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<EC2Computer> retentionStrategy,
            String initScript,
            String tmpDir,
            List<? extends NodeProperty<?>> nodeProperties,
            String remoteAdmin,
            String javaPath,
            String jvmopts,
            boolean stopOnTerminate,
            String idleTerminationMinutes,
            List<EC2Tag> tags,
            String cloudName,
            int launchTimeout,
            AMITypeData amiType,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            Tenancy tenancy,
            Boolean metadataEndpointEnabled,
            Boolean metadataTokensRequired,
            Integer metadataHopsLimit,
            Boolean metadataSupported,
            Boolean enclaveEnabled)
            throws FormException, IOException {
        super(name, remoteFS, launcher);
        setNumExecutors(numExecutors);
        setMode(mode);
        setLabelString(labelString);
        setRetentionStrategy(retentionStrategy);
        setNodeProperties(nodeProperties);

        this.instanceId = instanceId;
        this.templateDescription = templateDescription;
        this.initScript = initScript;
        this.tmpDir = tmpDir;
        this.remoteAdmin = remoteAdmin;
        this.javaPath = javaPath;
        this.jvmopts = jvmopts;
        this.stopOnTerminate = stopOnTerminate;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.tags = tags;
        this.usePrivateDnsName = connectionStrategy == ConnectionStrategy.PRIVATE_DNS;
        this.useDedicatedTenancy = tenancy == Tenancy.Dedicated;
        this.cloudName = cloudName;
        this.launchTimeout = launchTimeout;
        this.amiType = amiType;
        this.maxTotalUses = maxTotalUses;
        this.tenancy = tenancy != null ? tenancy : Tenancy.Default;
        this.metadataEndpointEnabled = metadataEndpointEnabled;
        this.metadataTokensRequired = metadataTokensRequired;
        this.metadataHopsLimit = metadataHopsLimit;
        this.metadataSupported = metadataSupported;
        this.enclaveEnabled = enclaveEnabled;
        readResolve();
    }

    @Deprecated
    public EC2AbstractSlave(
            String name,
            String instanceId,
            String templateDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<EC2Computer> retentionStrategy,
            String initScript,
            String tmpDir,
            List<? extends NodeProperty<?>> nodeProperties,
            String remoteAdmin,
            String javaPath,
            String jvmopts,
            boolean stopOnTerminate,
            String idleTerminationMinutes,
            List<EC2Tag> tags,
            String cloudName,
            int launchTimeout,
            AMITypeData amiType,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            Tenancy tenancy,
            Boolean metadataEndpointEnabled,
            Boolean metadataTokensRequired,
            Integer metadataHopsLimit,
            Boolean metadataSupported)
            throws FormException, IOException {
        this(
                name,
                instanceId,
                templateDescription,
                remoteFS,
                numExecutors,
                mode,
                labelString,
                launcher,
                retentionStrategy,
                initScript,
                tmpDir,
                nodeProperties,
                remoteAdmin,
                DEFAULT_JAVA_PATH,
                jvmopts,
                stopOnTerminate,
                idleTerminationMinutes,
                tags,
                cloudName,
                launchTimeout,
                amiType,
                connectionStrategy,
                maxTotalUses,
                tenancy,
                metadataEndpointEnabled,
                metadataTokensRequired,
                metadataHopsLimit,
                metadataSupported,
                DEFAULT_ENCLAVE_ENABLED);
    }

    @Deprecated
    public EC2AbstractSlave(
            String name,
            String instanceId,
            String templateDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<EC2Computer> retentionStrategy,
            String initScript,
            String tmpDir,
            List<? extends NodeProperty<?>> nodeProperties,
            String remoteAdmin,
            String javaPath,
            String jvmopts,
            boolean stopOnTerminate,
            String idleTerminationMinutes,
            List<EC2Tag> tags,
            String cloudName,
            int launchTimeout,
            AMITypeData amiType,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            Tenancy tenancy,
            Boolean metadataEndpointEnabled,
            Boolean metadataTokensRequired,
            Integer metadataHopsLimit)
            throws FormException, IOException {
        this(
                name,
                instanceId,
                templateDescription,
                remoteFS,
                numExecutors,
                mode,
                labelString,
                launcher,
                retentionStrategy,
                initScript,
                tmpDir,
                nodeProperties,
                remoteAdmin,
                DEFAULT_JAVA_PATH,
                jvmopts,
                stopOnTerminate,
                idleTerminationMinutes,
                tags,
                cloudName,
                launchTimeout,
                amiType,
                connectionStrategy,
                maxTotalUses,
                tenancy,
                metadataEndpointEnabled,
                metadataTokensRequired,
                metadataHopsLimit,
                DEFAULT_METADATA_SUPPORTED);
    }

    @Deprecated
    public EC2AbstractSlave(
            String name,
            String instanceId,
            String templateDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<EC2Computer> retentionStrategy,
            String initScript,
            String tmpDir,
            List<? extends NodeProperty<?>> nodeProperties,
            String remoteAdmin,
            String jvmopts,
            boolean stopOnTerminate,
            String idleTerminationMinutes,
            List<EC2Tag> tags,
            String cloudName,
            int launchTimeout,
            AMITypeData amiType,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            Tenancy tenancy)
            throws FormException, IOException {
        this(
                name,
                instanceId,
                templateDescription,
                remoteFS,
                numExecutors,
                mode,
                labelString,
                launcher,
                retentionStrategy,
                initScript,
                tmpDir,
                nodeProperties,
                remoteAdmin,
                DEFAULT_JAVA_PATH,
                jvmopts,
                stopOnTerminate,
                idleTerminationMinutes,
                tags,
                cloudName,
                launchTimeout,
                amiType,
                connectionStrategy,
                maxTotalUses,
                tenancy,
                DEFAULT_METADATA_ENDPOINT_ENABLED,
                DEFAULT_METADATA_TOKENS_REQUIRED,
                DEFAULT_METADATA_HOPS_LIMIT);
    }

    @Deprecated
    public EC2AbstractSlave(
            String name,
            String instanceId,
            String templateDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<EC2Computer> retentionStrategy,
            String initScript,
            String tmpDir,
            List<? extends NodeProperty<?>> nodeProperties,
            String remoteAdmin,
            String jvmopts,
            boolean stopOnTerminate,
            String idleTerminationMinutes,
            List<EC2Tag> tags,
            String cloudName,
            boolean useDedicatedTenancy,
            int launchTimeout,
            AMITypeData amiType,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses)
            throws FormException, IOException {

        this(
                name,
                instanceId,
                templateDescription,
                remoteFS,
                numExecutors,
                mode,
                labelString,
                launcher,
                retentionStrategy,
                initScript,
                tmpDir,
                nodeProperties,
                remoteAdmin,
                jvmopts,
                stopOnTerminate,
                idleTerminationMinutes,
                tags,
                cloudName,
                launchTimeout,
                amiType,
                connectionStrategy,
                maxTotalUses,
                Tenancy.backwardsCompatible(useDedicatedTenancy));
    }

    @Deprecated
    public EC2AbstractSlave(
            String name,
            String instanceId,
            String templateDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<EC2Computer> retentionStrategy,
            String initScript,
            String tmpDir,
            List<? extends NodeProperty<?>> nodeProperties,
            String remoteAdmin,
            String jvmopts,
            boolean stopOnTerminate,
            String idleTerminationMinutes,
            List<EC2Tag> tags,
            String cloudName,
            boolean usePrivateDnsName,
            boolean useDedicatedTenancy,
            int launchTimeout,
            AMITypeData amiType)
            throws FormException, IOException {
        this(
                name,
                instanceId,
                templateDescription,
                remoteFS,
                numExecutors,
                mode,
                labelString,
                launcher,
                retentionStrategy,
                initScript,
                tmpDir,
                nodeProperties,
                remoteAdmin,
                jvmopts,
                stopOnTerminate,
                idleTerminationMinutes,
                tags,
                cloudName,
                useDedicatedTenancy,
                launchTimeout,
                amiType,
                ConnectionStrategy.backwardsCompatible(usePrivateDnsName, false, false),
                -1);
    }

    @Serial
    @Override
    protected Object readResolve() {
        var o = (EC2AbstractSlave) super.readResolve();
        /*
         * If instanceId is null, this object was deserialized from an old version of the plugin, where this field did
         * not exist (prior to version 1.18). In those versions, the node name *was* the instance ID, so we can get it
         * from there.
         */
        if (o.instanceId == null) {
            o.instanceId = getNodeName();
        }

        if (o.amiType == null) {
            o.amiType = new UnixData(
                    o.rootCommandPrefix, o.slaveCommandPrefix, o.slaveCommandSuffix, Integer.toString(o.sshPort), null);
        }

        if (o.maxTotalUses == 0) {
            EC2Cloud cloud = getCloud();
            if (cloud != null) {
                SlaveTemplate template = cloud.getTemplate(o.templateDescription);
                if (template != null) {
                    if (template.getMaxTotalUses() == -1) {
                        o.maxTotalUses = -1;
                    }
                }
            }
        }

        /*
         * If this field is null (as it would be if this object is deserialized and not constructed normally) then
         * we need to explicitly initialize it, otherwise we will cause major blocker issues such as this one which
         * made Jenkins entirely unusable for some in the 1.50 release:
         * https://issues.jenkins-ci.org/browse/JENKINS-62043
         */
        if (o.terminateScheduled == null) {
            o.terminateScheduled = new ResettableCountDownLatch(1, false);
        }

        return o;
    }

    public EC2Cloud getCloud() {
        return (EC2Cloud) Jenkins.get().getCloud(cloudName);
    }

    /**
     * See http://aws.amazon.com/ec2/instance-types/
     */
    /* package */ static int toNumExecutors(InstanceType it) {
        switch (it) {
            case T1_MICRO:
                return 1;
            case M1_SMALL:
                return 1;
            case M1_MEDIUM:
                return 2;
            case M3_MEDIUM:
                return 2;
            case T3_NANO:
                return 2;
            case T3_A_NANO:
                return 2;
            case T3_MICRO:
                return 2;
            case T3_A_MICRO:
                return 2;
            case T3_SMALL:
                return 2;
            case T3_A_SMALL:
                return 2;
            case T3_MEDIUM:
                return 2;
            case T3_A_MEDIUM:
                return 2;
            case A1_LARGE:
                return 2;
            case T3_LARGE:
                return 3;
            case T3_A_LARGE:
                return 3;
            case M1_LARGE:
                return 4;
            case M3_LARGE:
                return 4;
            case M4_LARGE:
                return 4;
            case M5_LARGE:
                return 4;
            case M5_A_LARGE:
                return 4;
            case T3_XLARGE:
                return 5;
            case T3_A_XLARGE:
                return 5;
            case A1_XLARGE:
                return 5;
            case C1_MEDIUM:
                return 5;
            case M2_XLARGE:
                return 6;
            case C3_LARGE:
                return 7;
            case C4_LARGE:
                return 7;
            case C5_LARGE:
                return 7;
            case C5_D_LARGE:
                return 7;
            case M1_XLARGE:
                return 8;
            case T3_2_XLARGE:
                return 10;
            case T3_A_2_XLARGE:
                return 10;
            case A1_2_XLARGE:
                return 10;
            case M2_2_XLARGE:
                return 13;
            case M3_XLARGE:
                return 13;
            case M4_XLARGE:
                return 13;
            case M5_XLARGE:
                return 13;
            case M5_A_XLARGE:
                return 13;
            case A1_4_XLARGE:
                return 14;
            case C3_XLARGE:
                return 14;
            case C4_XLARGE:
                return 14;
            case C5_XLARGE:
                return 14;
            case C5_D_XLARGE:
                return 14;
            case C1_XLARGE:
                return 20;
            case M2_4_XLARGE:
                return 26;
            case M3_2_XLARGE:
                return 26;
            case M4_2_XLARGE:
                return 26;
            case M5_2_XLARGE:
                return 26;
            case M5_A_2_XLARGE:
                return 26;
            case G2_2_XLARGE:
                return 26;
            case C3_2_XLARGE:
                return 28;
            case C4_2_XLARGE:
                return 28;
            case C5_2_XLARGE:
                return 28;
            case C5_D_2_XLARGE:
                return 28;
            case CC1_4_XLARGE:
                return 33;
            case CG1_4_XLARGE:
                return 33;
            case HI1_4_XLARGE:
                return 35;
            case HS1_8_XLARGE:
                return 35;
            case C3_4_XLARGE:
                return 55;
            case C4_4_XLARGE:
                return 55;
            case C5_4_XLARGE:
                return 55;
            case C5_D_4_XLARGE:
                return 55;
            case M4_4_XLARGE:
                return 55;
            case M5_4_XLARGE:
                return 55;
            case M5_A_4_XLARGE:
                return 55;
            case CC2_8_XLARGE:
                return 88;
            case CR1_8_XLARGE:
                return 88;
            case C3_8_XLARGE:
                return 108;
            case C4_8_XLARGE:
                return 108;
            case C5_9_XLARGE:
                return 108;
            case C5_D_9_XLARGE:
                return 108;
            case M4_10_XLARGE:
                return 120;
            case M5_12_XLARGE:
                return 120;
            case M5_A_12_XLARGE:
                return 120;
            case M4_16_XLARGE:
                return 160;
            case C5_18_XLARGE:
                return 216;
            case C5_D_18_XLARGE:
                return 216;
            case M5_24_XLARGE:
                return 240;
            case M5_A_24_XLARGE:
                return 240;
            case DL1_24_XLARGE:
                return 250;
            case MAC1_METAL:
                return 1;
            default:
                return 1;
        }
    }

    /**
     * EC2 instance ID.
     */
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public Computer createComputer() {
        return new EC2Computer(this);
    }

    /**
     * Returns view of AWS EC2 Instance.
     *
     * @param instanceId instance id.
     * @param cloud cloud provider (EC2Cloud compatible).
     * @return instance in EC2.
     */
    public static Instance getInstance(String instanceId, EC2Cloud cloud) {
        Instance i = null;
        try {
            i = CloudHelper.getInstanceWithRetry(instanceId, cloud);
        } catch (InterruptedException e) {
            // We'll just retry next time we test for idleness.
            LOGGER.fine("InterruptedException while get " + instanceId + " Exception: " + e);
        }
        return i;
    }
    /**
     * Terminates the instance in EC2.
     */
    public abstract Future<?> terminate();

    void stop() {
        try {
            Ec2Client ec2 = getCloud().connect();
            StopInstancesRequest request = StopInstancesRequest.builder()
                    .instanceIds(Collections.singletonList(getInstanceId()))
                    .build();
            LOGGER.fine("Sending stop request for " + getInstanceId());
            ec2.stopInstances(request);
            LOGGER.info("EC2 instance stop request sent for " + getInstanceId());
            Computer computer = toComputer();
            if (computer != null) {
                computer.disconnect(null);
            }
        } catch (SdkException e) {
            LOGGER.log(Level.WARNING, "Failed to stop EC2 instance: " + getInstanceId(), e);
        }
    }

    boolean terminateInstance() {
        try {
            Ec2Client ec2 = getCloud().connect();
            TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                    .instanceIds(Collections.singletonList(getInstanceId()))
                    .build();
            LOGGER.fine("Sending terminate request for " + getInstanceId());
            ec2.terminateInstances(request);
            LOGGER.info("EC2 instance terminate request sent for " + getInstanceId());
            return true;
        } catch (SdkException e) {
            LOGGER.log(Level.WARNING, "Failed to terminate EC2 instance: " + getInstanceId(), e);
            return false;
        }
    }

    @Override
    public Node reconfigure(final StaplerRequest2 req, JSONObject form) throws FormException {
        if (form == null) {
            return null;
        }

        EC2AbstractSlave result = (EC2AbstractSlave) super.reconfigure(req, form);

        if (result != null) {
            /* Get rid of the old tags, as represented by ourselves. */
            clearLiveInstancedata();

            /* Set the new tags, as represented by our successor */
            result.pushLiveInstancedata();
            return result;
        }
        return null;
    }

    @Override
    public boolean isAcceptingTasks() {
        return terminateScheduled.getCount() == 0;
    }

    void idleTimeout() {
        LOGGER.info("EC2 instance idle time expired: " + getInstanceId());
        if (!stopOnTerminate) {
            terminate();
        } else {
            stop();
        }
    }

    void launchTimeout() {
        LOGGER.info("EC2 instance failed to launch: " + getInstanceId());
        terminate();
    }

    public long getLaunchTimeoutInMillis() {
        // this should be fine as long as launchTimeout remains an int type
        return launchTimeout * 1000L;
    }

    public String getRemoteAdmin() {
        if (remoteAdmin == null || remoteAdmin.isEmpty()) {
            return amiType.isWindows() ? "Administrator" : "root";
        }
        return remoteAdmin;
    }

    String getRootCommandPrefix() {
        String commandPrefix = amiType.isSSHAgent() ? ((SSHData) amiType).getRootCommandPrefix() : "";
        if (commandPrefix == null || commandPrefix.isEmpty()) {
            return "";
        }
        return commandPrefix + " ";
    }

    String getSlaveCommandPrefix() {
        String commandPrefix = amiType.isSSHAgent() ? ((SSHData) amiType).getSlaveCommandPrefix() : "";
        if (commandPrefix == null || commandPrefix.isEmpty()) {
            return "";
        }
        return commandPrefix + " ";
    }

    String getSlaveCommandSuffix() {
        String commandSuffix = amiType.isSSHAgent() ? ((SSHData) amiType).getSlaveCommandSuffix() : "";
        if (commandSuffix == null || commandSuffix.isEmpty()) {
            return "";
        }
        return " " + commandSuffix;
    }

    String getJavaPath() {
        return Util.fixNull(javaPath);
    }

    String getJvmopts() {
        return Util.fixNull(jvmopts);
    }

    public int getSshPort() {
        String sshPort = amiType.isSSHAgent() ? ((SSHData) amiType).getSshPort() : "22";
        if (sshPort == null || sshPort.isEmpty()) {
            return 22;
        }

        int port = 0;
        try {
            port = Integer.parseInt(sshPort);
        } catch (Exception e) {
        }
        return port != 0 ? port : 22;
    }

    public boolean getStopOnTerminate() {
        return stopOnTerminate;
    }

    /**
     * Called when the agent is connected to Jenkins
     */
    public void onConnected() {
        isConnected = true;
    }

    protected boolean isAlive(boolean force) {
        fetchLiveInstanceData(force);
        if (lastFetchInstance == null) {
            return false;
        }
        if (lastFetchInstance.state().name().equals(InstanceStateName.TERMINATED)) {
            return false;
        }
        return true;
    }

    /*
     * Much of the EC2 data is beyond our direct control, therefore we need to refresh it from time to time to ensure we
     * reflect the reality of the instances.
     */
    private void fetchLiveInstanceData(boolean force) throws SdkException {
        /*
         * If we've grabbed the data recently, don't bother getting it again unless we are forced
         */
        long now = System.currentTimeMillis();
        if ((lastFetchTime > 0) && (now - lastFetchTime < MIN_FETCH_TIME) && !force) {
            return;
        }

        if (getInstanceId() == null || getInstanceId().isEmpty()) {
            /*
             * The getInstanceId() implementation on EC2SpotSlave can return null if the spot request doesn't yet know
             * the instance id that it is starting. What happens is that null is passed to getInstanceId() which
             * searches AWS but without an instanceID the search returns some random box. We then fetch its metadata,
             * including tags, and then later, when the spot request eventually gets the instanceID correctly we push
             * the saved tags from that random box up to the new spot resulting in confusion and delay.
             */
            return;
        }

        Instance i = null;
        try {
            i = CloudHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        } catch (InterruptedException e) {
            // We'll just retry next time we test for idleness.
            LOGGER.fine("InterruptedException while get " + getInstanceId() + " Exception: " + e);
            return;
        }

        lastFetchTime = now;
        lastFetchInstance = i;
        if (i == null) {
            return;
        }

        publicDNS = i.publicDnsName();
        privateDNS = i.privateIpAddress();
        createdTime = i.launchTime();
        instanceType = i.instanceType().name();

        /*
         * Only fetch tags from live instance if tags are set. This check is required to mitigate a race condition
         * when fetchLiveInstanceData() is called before pushLiveInstancedata().
         */
        if (!i.tags().isEmpty()) {
            tags = new LinkedList<>();
            for (Tag t : i.tags()) {
                tags.add(new EC2Tag(t.key(), t.value()));
            }
        }
    }

    /*
     * Clears all existing tag data so that we can force the instance into a known state
     */
    protected void clearLiveInstancedata() throws SdkException {
        Instance inst = null;
        try {
            inst = CloudHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        } catch (InterruptedException e) {
            // We'll just retry next time we test for idleness.
            LOGGER.fine("InterruptedException while get " + getInstanceId() + " Exception: " + e);
            return;
        }

        /* Now that we have our instance, we can clear the tags on it */
        if (!tags.isEmpty()) {
            HashSet<Tag> instTags = new HashSet<>();

            for (EC2Tag t : tags) {
                instTags.add(Tag.builder().key(t.getName()).value(t.getValue()).build());
            }

            List<String> resources = getResourcesToTag(inst);
            DeleteTagsRequest tagRequest = DeleteTagsRequest.builder()
                    .resources(resources)
                    .tags(instTags)
                    .build();
            getCloud().connect().deleteTags(tagRequest);
        }
    }

    /*
     * Sets tags on an instance and on the volumes attached to it. This will not clear existing tag data, so call
     * clearLiveInstancedata if needed
     */
    protected void pushLiveInstancedata() throws SdkException {
        Instance inst = null;
        try {
            inst = CloudHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        } catch (InterruptedException e) {
            // We'll just retry next time we test for idleness.
            LOGGER.fine("InterruptedException while get " + getInstanceId() + " Exception: " + e);
        }

        /* Now that we have our instance, we can set tags on it */
        if (inst != null && tags != null && !tags.isEmpty()) {
            HashSet<Tag> instTags = new HashSet<>();

            for (EC2Tag t : tags) {
                instTags.add(Tag.builder().key(t.getName()).value(t.getValue()).build());
            }

            List<String> resources = getResourcesToTag(inst);
            CreateTagsRequest tagRequest = CreateTagsRequest.builder()
                    .resources(resources)
                    .tags(instTags)
                    .build();
            getCloud().connect().createTags(tagRequest);
        }
    }

    /*
     * Get resources to tag, that is the instance itself and the volumes attached to it.
     */
    private List<String> getResourcesToTag(Instance inst) {
        List<String> resources = new ArrayList<>();
        resources.add(inst.instanceId());
        for (InstanceBlockDeviceMapping blockDeviceMapping : inst.blockDeviceMappings()) {
            resources.add(blockDeviceMapping.ebs().volumeId());
        }
        return resources;
    }

    public String getPublicDNS() {
        fetchLiveInstanceData(false);
        return publicDNS;
    }

    public String getPrivateDNS() {
        fetchLiveInstanceData(false);
        return privateDNS;
    }

    public String getInstanceType() {
        fetchLiveInstanceData(false);
        return instanceType;
    }

    public List<EC2Tag> getTags() {
        fetchLiveInstanceData(false);
        return Collections.unmodifiableList(tags);
    }

    public Instant getCreatedTime() {
        fetchLiveInstanceData(false);
        return createdTime;
    }

    @Deprecated
    public boolean getUsePrivateDnsName() {
        return usePrivateDnsName;
    }

    public Secret getAdminPassword() {
        return amiType.isWinRMAgent() ? ((WindowsData) amiType).getPassword() : Secret.fromString("");
    }

    public boolean isUseHTTPS() {
        return amiType.isWinRMAgent() && ((WindowsData) amiType).isUseHTTPS();
    }

    public int getBootDelay() {
        return amiType.getBootDelayInMillis();
    }

    public Boolean getMetadataSupported() {
        return metadataSupported;
    }

    public Boolean getMetadataEndpointEnabled() {
        return metadataEndpointEnabled;
    }

    public Boolean getMetadataTokensRequired() {
        return metadataTokensRequired;
    }

    public Integer getMetadataHopsLimit() {
        return metadataHopsLimit;
    }

    public Boolean getEnclaveEnabled() {
        return enclaveEnabled;
    }

    public boolean isSpecifyPassword() {
        return amiType.isWinRMAgent() && ((WindowsData) amiType).isSpecifyPassword();
    }

    public boolean isAllowSelfSignedCertificate() {
        return amiType.isWinRMAgent() && ((WindowsData) amiType).isAllowSelfSignedCertificate();
    }

    public static ListBoxModel fillZoneItems(AwsCredentialsProvider credentialsProvider, String region) {
        ListBoxModel model = new ListBoxModel();

        if (!StringUtils.isEmpty(region)) {
            Ec2Client client =
                    AmazonEC2Factory.getInstance().connect(credentialsProvider, EC2Cloud.parseRegion(region), null);
            DescribeAvailabilityZonesResponse zones = client.describeAvailabilityZones();
            List<AvailabilityZone> zoneList = zones.availabilityZones();
            model.add("<not specified>", "");
            for (AvailabilityZone z : zoneList) {
                model.add(z.zoneName(), z.zoneName());
            }
        }
        return model;
    }

    /*
     * Used to determine if the agent is On Demand or Spot
     */
    public abstract String getEc2Type();

    public abstract static class DescriptorImpl extends SlaveDescriptor {

        @Override
        public abstract String getDisplayName();

        @Override
        public boolean isInstantiable() {
            return false;
        }

        @POST
        public ListBoxModel doFillZoneItems(
                @QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId,
                @QueryParameter String region,
                @QueryParameter String roleArn,
                @QueryParameter String roleSessionName) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new ListBoxModel();
            }
            AwsCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(
                    useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
            return fillZoneItems(credentialsProvider, region);
        }

        public List<Descriptor<AMITypeData>> getAMITypeDescriptors() {
            return Jenkins.get().getDescriptorList(AMITypeData.class);
        }
    }
}
