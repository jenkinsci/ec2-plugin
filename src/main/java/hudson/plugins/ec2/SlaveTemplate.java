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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Failure;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.model.listeners.SaveableListener;
import hudson.plugins.ec2.util.AmazonEC2Factory;
import hudson.plugins.ec2.util.DeviceMappingParser;
import hudson.plugins.ec2.util.EC2AgentConfig;
import hudson.plugins.ec2.util.EC2AgentFactory;
import hudson.plugins.ec2.util.InstanceTypeCompat;
import hudson.plugins.ec2.util.KeyPair;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import hudson.plugins.ec2.util.MinimumNumberOfInstancesTimeRangeConfig;
import hudson.plugins.ec2.monitoring.EC2ProvisioningMonitor;
import hudson.plugins.ec2.monitoring.ProvisioningEvent;
import hudson.security.Permission;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.slaves.iterators.api.NodeIterator;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.CancelSpotInstanceRequestsRequest;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.CreditSpecificationRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ec2.model.DeviceType;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.EnclaveOptionsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.HttpTokensState;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceMarketOptionsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataEndpointState;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.InstanceTypeHypervisor;
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo;
import software.amazon.awssdk.services.ec2.model.MarketType;
import software.amazon.awssdk.services.ec2.model.NitroEnclavesSupport;
import software.amazon.awssdk.services.ec2.model.Placement;
import software.amazon.awssdk.services.ec2.model.RequestSpotInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RequestSpotInstancesResponse;
import software.amazon.awssdk.services.ec2.model.RequestSpotLaunchSpecification;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesMonitoringEnabled;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.ShutdownBehavior;
import software.amazon.awssdk.services.ec2.model.SpotInstanceRequest;
import software.amazon.awssdk.services.ec2.model.SpotMarketOptions;
import software.amazon.awssdk.services.ec2.model.SpotPlacement;
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest;
import software.amazon.awssdk.services.ec2.model.StartInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

/**
 * Template of {@link EC2AbstractSlave} to launch.
 *
 * @author Kohsuke Kawaguchi
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {
    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());

    private static final String EC2_RESOURCE_ID_DELIMETERS = "[\\s,;]+";

    public String ami;

    public final String description;

    public final String zone;

    public final SpotConfiguration spotConfig;

    public final String securityGroups;

    public final String remoteFS;

    public String type;

    public final boolean ebsOptimized;

    public final boolean monitoring;

    public final boolean t2Unlimited;

    public final String labels;

    public final Node.Mode mode;

    public final String initScript;

    public final String tmpDir;

    public final String userData;

    public final String numExecutors;

    public final String remoteAdmin;

    public String javaPath;

    public final String jvmopts;

    public final String subnetId;

    public final String idleTerminationMinutes;

    private boolean terminateIdleDuringShutdown;

    public final String iamInstanceProfile;

    public final boolean deleteRootOnTermination;

    public final boolean useEphemeralDevices;

    public final String customDeviceMapping;

    public int instanceCap;

    private final int minimumNumberOfInstances;

    private MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig;

    private final int minimumNumberOfSpareInstances;

    public final boolean stopOnTerminate;

    private final List<EC2Tag> tags;

    public ConnectionStrategy connectionStrategy;

    public HostKeyVerificationStrategyEnum hostKeyVerificationStrategy;

    public AssociateIPStrategy associateIPStrategy;

    @Deprecated
    public transient boolean associatePublicIp;

    protected transient EC2Cloud parent;

    public AMITypeData amiType;

    public int launchTimeout;

    public boolean connectBySSHProcess;

    public int maxTotalUses;

    private boolean avoidUsingOrphanedNodes;

    private /* lazily initialized */ DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProperties;

    public int nextSubnet;

    public String currentSubnetId;

    public Tenancy tenancy;

    public EbsEncryptRootVolume ebsEncryptRootVolume;

    private Boolean metadataSupported;

    private Boolean metadataEndpointEnabled;

    private Boolean metadataTokensRequired;

    private Integer metadataHopsLimit;

    private Boolean enclaveEnabled;

    private transient /* almost final */ Set<LabelAtom> labelSet;

    private transient /* almost final */ Set<String> securityGroupSet;

    /* FIXME: Ideally these would be List<String>, but Jenkins currently
     * doesn't offer a usable way to represent those in forms. Instead
     * the values are interpreted as a comma separated list.
     *
     * https://issues.jenkins.io/browse/JENKINS-27901
     */
    @CheckForNull
    private String amiOwners;

    @CheckForNull
    private String amiUsers;

    @CheckForNull
    private List<EC2Filter> amiFilters;

    /*
     * Necessary to handle reading from old configurations. The UnixData object is created in readResolve()
     */
    @Deprecated
    public transient String sshPort;

    @Deprecated
    public transient String rootCommandPrefix;

    @Deprecated
    public transient String slaveCommandPrefix;

    @Deprecated
    public transient String slaveCommandSuffix;

    @Deprecated
    public boolean usePrivateDnsName;

    @Deprecated
    public boolean connectUsingPublicIp;

    @Deprecated
    public transient boolean useDedicatedTenancy;

    @DataBoundConstructor
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            String type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String javaPath,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            int minimumNumberOfSpareInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            String launchTimeoutStr,
            AssociateIPStrategy associateIPStrategy,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties,
            HostKeyVerificationStrategyEnum hostKeyVerificationStrategy,
            Tenancy tenancy,
            EbsEncryptRootVolume ebsEncryptRootVolume,
            Boolean metadataEndpointEnabled,
            Boolean metadataTokensRequired,
            Integer metadataHopsLimit,
            Boolean metadataSupported,
            Boolean enclaveEnabled) {

        if (StringUtils.isNotBlank(remoteAdmin) || StringUtils.isNotBlank(jvmopts) || StringUtils.isNotBlank(tmpDir)) {
            LOGGER.log(
                    Level.FINE,
                    "As remoteAdmin, jvmopts or tmpDir is not blank, we must ensure the user has ADMINISTER rights.");
            // Can be null during tests
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j != null) {
                j.checkPermission(Jenkins.ADMINISTER);
            }
        }

        this.ami = ami;
        this.zone = zone;
        this.spotConfig = spotConfig;
        this.securityGroups = securityGroups;
        this.remoteFS = remoteFS;
        this.amiType = amiType;
        this.type =
                type != null && !type.isEmpty() ? InstanceTypeCompat.of(type).toString() : null;
        this.ebsOptimized = ebsOptimized;
        this.labels = Util.fixNull(labelString);
        this.mode = mode != null ? mode : Node.Mode.NORMAL;
        this.description = description;
        this.initScript = initScript;
        this.tmpDir = tmpDir;
        this.userData = StringUtils.trimToEmpty(userData);
        this.numExecutors = Util.fixNull(numExecutors).trim();
        this.remoteAdmin = remoteAdmin;

        if (StringUtils.isNotBlank(javaPath)) {
            this.javaPath = javaPath;
        } else {
            this.javaPath = EC2AbstractSlave.DEFAULT_JAVA_PATH;
        }

        this.jvmopts = jvmopts;
        this.stopOnTerminate = stopOnTerminate;
        this.subnetId = subnetId;
        this.tags = tags;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.connectionStrategy = connectionStrategy == null ? ConnectionStrategy.PRIVATE_IP : connectionStrategy;
        this.useDedicatedTenancy = tenancy == Tenancy.Dedicated;
        this.connectBySSHProcess = connectBySSHProcess;
        this.maxTotalUses = maxTotalUses;
        this.nodeProperties = new DescribableList<>(Saveable.NOOP, Util.fixNull(nodeProperties));
        this.monitoring = monitoring;
        this.nextSubnet = 0;

        this.usePrivateDnsName = this.connectionStrategy.equals(ConnectionStrategy.PRIVATE_DNS);
        this.connectUsingPublicIp = this.connectionStrategy.equals(ConnectionStrategy.PUBLIC_IP);

        this.minimumNumberOfInstances = minimumNumberOfInstances;
        this.minimumNumberOfSpareInstances = minimumNumberOfSpareInstances;

        if (null == instanceCapStr || instanceCapStr.isEmpty()) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }

        try {
            this.launchTimeout = Integer.parseInt(launchTimeoutStr);
        } catch (NumberFormatException nfe) {
            this.launchTimeout = Integer.MAX_VALUE;
        }

        this.iamInstanceProfile = iamInstanceProfile;
        this.deleteRootOnTermination = deleteRootOnTermination;
        this.useEphemeralDevices = useEphemeralDevices;
        this.customDeviceMapping = customDeviceMapping;
        this.t2Unlimited = t2Unlimited;

        this.hostKeyVerificationStrategy = hostKeyVerificationStrategy != null
                ? hostKeyVerificationStrategy
                : HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT;
        this.tenancy = tenancy != null ? tenancy : Tenancy.Default;
        this.ebsEncryptRootVolume = ebsEncryptRootVolume != null ? ebsEncryptRootVolume : EbsEncryptRootVolume.DEFAULT;
        this.metadataSupported =
                metadataSupported != null ? metadataSupported : EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED;
        this.metadataEndpointEnabled = metadataEndpointEnabled != null
                ? metadataEndpointEnabled
                : EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED;
        this.metadataTokensRequired = metadataTokensRequired != null
                ? metadataTokensRequired
                : EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED;
        this.metadataHopsLimit =
                metadataHopsLimit != null ? metadataHopsLimit : EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT;
        this.enclaveEnabled = enclaveEnabled != null ? enclaveEnabled : EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED;
        this.associateIPStrategy = associateIPStrategy != null ? associateIPStrategy : AssociateIPStrategy.DEFAULT;

        readResolve(); // initialize
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            String type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String javaPath,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            int minimumNumberOfSpareInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties,
            HostKeyVerificationStrategyEnum hostKeyVerificationStrategy,
            Tenancy tenancy,
            EbsEncryptRootVolume ebsEncryptRootVolume,
            Boolean metadataEndpointEnabled,
            Boolean metadataTokensRequired,
            Integer metadataHopsLimit,
            Boolean metadataSupported,
            Boolean enclaveEnabled) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                InstanceType.fromValue(type.toString()).toString(),
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                javaPath,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                minimumNumberOfInstances,
                minimumNumberOfSpareInstances,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                launchTimeoutStr,
                AssociateIPStrategy.backwardsCompatible(associatePublicIp),
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses,
                nodeProperties,
                hostKeyVerificationStrategy,
                tenancy,
                ebsEncryptRootVolume,
                metadataEndpointEnabled,
                metadataTokensRequired,
                metadataHopsLimit,
                metadataSupported,
                enclaveEnabled);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String javaPath,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            int minimumNumberOfSpareInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties,
            HostKeyVerificationStrategyEnum hostKeyVerificationStrategy,
            Tenancy tenancy,
            EbsEncryptRootVolume ebsEncryptRootVolume,
            Boolean metadataEndpointEnabled,
            Boolean metadataTokensRequired,
            Integer metadataHopsLimit,
            Boolean metadataSupported) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                InstanceType.fromValue(type.toString()).toString(),
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                javaPath,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                minimumNumberOfInstances,
                minimumNumberOfSpareInstances,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses,
                nodeProperties,
                hostKeyVerificationStrategy,
                tenancy,
                ebsEncryptRootVolume,
                metadataEndpointEnabled,
                metadataTokensRequired,
                metadataHopsLimit,
                metadataSupported,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String javaPath,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            int minimumNumberOfSpareInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties,
            HostKeyVerificationStrategyEnum hostKeyVerificationStrategy,
            Tenancy tenancy,
            EbsEncryptRootVolume ebsEncryptRootVolume,
            Boolean metadataSupported,
            Boolean metadataEndpointEnabled,
            Boolean metadataTokensRequired,
            Integer metadataHopsLimit) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                InstanceType.fromValue(type.toString()).toString(),
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                minimumNumberOfInstances,
                minimumNumberOfSpareInstances,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses,
                nodeProperties,
                hostKeyVerificationStrategy,
                tenancy,
                null,
                metadataEndpointEnabled,
                metadataTokensRequired,
                metadataHopsLimit,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            int minimumNumberOfSpareInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties,
            HostKeyVerificationStrategyEnum hostKeyVerificationStrategy,
            Tenancy tenancy,
            EbsEncryptRootVolume ebsEncryptRootVolume) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                InstanceType.fromValue(type.toString()).toString(),
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                minimumNumberOfInstances,
                minimumNumberOfSpareInstances,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses,
                nodeProperties,
                hostKeyVerificationStrategy,
                tenancy,
                null,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            int minimumNumberOfSpareInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties,
            HostKeyVerificationStrategyEnum hostKeyVerificationStrategy,
            Tenancy tenancy) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                minimumNumberOfInstances,
                minimumNumberOfSpareInstances,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses,
                nodeProperties,
                hostKeyVerificationStrategy,
                tenancy,
                null);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            int minimumNumberOfSpareInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            boolean useDedicatedTenancy,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties,
            HostKeyVerificationStrategyEnum hostKeyVerificationStrategy) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                minimumNumberOfInstances,
                minimumNumberOfSpareInstances,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses,
                nodeProperties,
                hostKeyVerificationStrategy,
                Tenancy.backwardsCompatible(useDedicatedTenancy));
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            int minimumNumberOfSpareInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            boolean useDedicatedTenancy,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                minimumNumberOfInstances,
                minimumNumberOfSpareInstances,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                useDedicatedTenancy,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses,
                nodeProperties,
                null);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            boolean useDedicatedTenancy,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                minimumNumberOfInstances,
                0,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                useDedicatedTenancy,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses,
                nodeProperties);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            int minimumNumberOfInstances,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            boolean useDedicatedTenancy,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                minimumNumberOfInstances,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                useDedicatedTenancy,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses,
                Collections.emptyList());
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            boolean useDedicatedTenancy,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean monitoring,
            boolean t2Unlimited,
            ConnectionStrategy connectionStrategy,
            int maxTotalUses) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                0,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                useDedicatedTenancy,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                connectionStrategy,
                maxTotalUses);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            boolean usePrivateDnsName,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            boolean useDedicatedTenancy,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean connectUsingPublicIp,
            boolean monitoring,
            boolean t2Unlimited) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                useDedicatedTenancy,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                monitoring,
                t2Unlimited,
                ConnectionStrategy.backwardsCompatible(usePrivateDnsName, connectUsingPublicIp, associatePublicIp),
                -1);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            boolean usePrivateDnsName,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean deleteRootOnTermination,
            boolean useEphemeralDevices,
            boolean useDedicatedTenancy,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess,
            boolean connectUsingPublicIp) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                usePrivateDnsName,
                instanceCapStr,
                iamInstanceProfile,
                deleteRootOnTermination,
                useEphemeralDevices,
                useDedicatedTenancy,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                connectUsingPublicIp,
                false,
                false);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            boolean usePrivateDnsName,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean useEphemeralDevices,
            boolean useDedicatedTenancy,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping,
            boolean connectBySSHProcess) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                usePrivateDnsName,
                instanceCapStr,
                iamInstanceProfile,
                false,
                useEphemeralDevices,
                useDedicatedTenancy,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                connectBySSHProcess,
                false);
    }

    @Deprecated
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            AMITypeData amiType,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            boolean usePrivateDnsName,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean useEphemeralDevices,
            boolean useDedicatedTenancy,
            String launchTimeoutStr,
            boolean associatePublicIp,
            String customDeviceMapping) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                amiType,
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                usePrivateDnsName,
                instanceCapStr,
                iamInstanceProfile,
                useEphemeralDevices,
                useDedicatedTenancy,
                launchTimeoutStr,
                associatePublicIp,
                customDeviceMapping,
                false);
    }

    /**
     * Backward compatible constructor for reloading previous version data
     */
    public SlaveTemplate(
            String ami,
            String zone,
            SpotConfiguration spotConfig,
            String securityGroups,
            String remoteFS,
            String sshPort,
            com.amazonaws.services.ec2.model.InstanceType type,
            boolean ebsOptimized,
            String labelString,
            Node.Mode mode,
            String description,
            String initScript,
            String tmpDir,
            String userData,
            String numExecutors,
            String remoteAdmin,
            String rootCommandPrefix,
            String slaveCommandPrefix,
            String slaveCommandSuffix,
            String jvmopts,
            boolean stopOnTerminate,
            String subnetId,
            List<EC2Tag> tags,
            String idleTerminationMinutes,
            boolean usePrivateDnsName,
            String instanceCapStr,
            String iamInstanceProfile,
            boolean useEphemeralDevices,
            String launchTimeoutStr) {
        this(
                ami,
                zone,
                spotConfig,
                securityGroups,
                remoteFS,
                type,
                ebsOptimized,
                labelString,
                mode,
                description,
                initScript,
                tmpDir,
                userData,
                numExecutors,
                remoteAdmin,
                new UnixData(rootCommandPrefix, slaveCommandPrefix, slaveCommandSuffix, sshPort, null),
                jvmopts,
                stopOnTerminate,
                subnetId,
                tags,
                idleTerminationMinutes,
                usePrivateDnsName,
                instanceCapStr,
                iamInstanceProfile,
                useEphemeralDevices,
                false,
                launchTimeoutStr,
                false,
                null);
    }

    public boolean isConnectBySSHProcess() {
        // See
        // src/main/resources/hudson/plugins/ec2/SlaveTemplate/help-connectBySSHProcess.html
        return connectBySSHProcess;
    }

    public EC2Cloud getParent() {
        return parent;
    }

    public String getLabelString() {
        return labels;
    }

    public Node.Mode getMode() {
        return mode;
    }

    public String getDisplayName() {
        return String.format("EC2 (%s) - %s", parent.getDisplayName(), description);
    }

    public String getSlaveName(String instanceId) {
        final String agentName = String.format("%s (%s)", getDisplayName(), instanceId);
        try {
            Jenkins.checkGoodName(agentName);
            return agentName;
        } catch (Failure e) {
            return instanceId;
        }
    }

    String getZone() {
        return zone;
    }

    public String getSecurityGroupString() {
        return securityGroups;
    }

    public Set<String> getSecurityGroupSet() {
        return securityGroupSet;
    }

    public Set<String> parseSecurityGroups() {
        if (securityGroups == null || securityGroups.trim().isEmpty()) {
            return Collections.emptySet();
        } else {
            return new HashSet<>(Arrays.asList(securityGroups.split("\\s*,\\s*")));
        }
    }

    public int getNumExecutors() {
        try {
            return Integer.parseInt(numExecutors);
        } catch (NumberFormatException e) {
            return EC2AbstractSlave.toNumExecutors(InstanceType.fromValue(type));
        }
    }

    public int getSshPort() {
        try {
            String sshPort = "";
            if (amiType.isSSHAgent()) {
                sshPort = ((SSHData) amiType).getSshPort();
            }
            return Integer.parseInt(sshPort);
        } catch (NumberFormatException e) {
            return 22;
        }
    }

    public String getRemoteAdmin() {
        return remoteAdmin;
    }

    public String getRootCommandPrefix() {
        return amiType.isSSHAgent() ? ((SSHData) amiType).getRootCommandPrefix() : "";
    }

    public String getSlaveCommandPrefix() {
        return amiType.isSSHAgent() ? ((SSHData) amiType).getSlaveCommandPrefix() : "";
    }

    public String getSlaveCommandSuffix() {
        return amiType.isSSHAgent() ? ((SSHData) amiType).getSlaveCommandSuffix() : "";
    }

    public String chooseSubnetId() {
        if (StringUtils.isBlank(subnetId)) {
            return null;
        } else {
            String[] subnetIdList = getSubnetId().split(EC2_RESOURCE_ID_DELIMETERS);

            // Round-robin subnet selection.
            currentSubnetId = subnetIdList[nextSubnet];
            nextSubnet = (nextSubnet + 1) % subnetIdList.length;

            return currentSubnetId;
        }
    }

    public String chooseSubnetId(boolean rotateSubnet) {
        if (rotateSubnet) {
            return chooseSubnetId();
        } else {
            return this.currentSubnetId;
        }
    }

    public String getSubnetId() {
        return subnetId;
    }

    public String getCurrentSubnetId() {
        return currentSubnetId;
    }

    @Deprecated
    public boolean getAssociatePublicIp() {
        return AssociateIPStrategy.PUBLIC_IP == associateIPStrategy;
    }

    public AssociateIPStrategy getAssociateIPStrategy() {
        return associateIPStrategy;
    }

    @Deprecated
    @DataBoundSetter
    public void setConnectUsingPublicIp(boolean connectUsingPublicIp) {
        this.connectUsingPublicIp = connectUsingPublicIp;
        this.connectionStrategy = ConnectionStrategy.backwardsCompatible(
                this.usePrivateDnsName, this.connectUsingPublicIp, getAssociatePublicIp());
    }

    @Deprecated
    @DataBoundSetter
    public void setUsePrivateDnsName(boolean usePrivateDnsName) {
        this.usePrivateDnsName = usePrivateDnsName;
        this.connectionStrategy = ConnectionStrategy.backwardsCompatible(
                this.usePrivateDnsName, this.connectUsingPublicIp, getAssociatePublicIp());
    }

    @Deprecated
    public boolean getUsePrivateDnsName() {
        return usePrivateDnsName;
    }

    @Deprecated
    public boolean isConnectUsingPublicIp() {
        return connectUsingPublicIp;
    }

    public List<EC2Tag> getTags() {
        if (null == tags) {
            return null;
        }
        return Collections.unmodifiableList(tags);
    }

    public String getidleTerminationMinutes() {
        return idleTerminationMinutes;
    }

    public boolean getTerminateIdleDuringShutdown() {
        return terminateIdleDuringShutdown;
    }

    @DataBoundSetter
    public void setTerminateIdleDuringShutdown(boolean terminateIdleDuringShutdown) {
        this.terminateIdleDuringShutdown = terminateIdleDuringShutdown;
    }

    public Set<LabelAtom> getLabelSet() {
        if (labelSet == null) {
            labelSet = Label.parse(labels);
        }
        return labelSet;
    }

    public String getAmi() {
        return ami;
    }

    public void setAmi(String ami) {
        this.ami = ami;
    }

    public AMITypeData getAmiType() {
        return amiType;
    }

    public void setAmiType(AMITypeData amiType) {
        this.amiType = amiType;
    }

    public int getMinimumNumberOfInstances() {
        return minimumNumberOfInstances;
    }

    public int getMinimumNumberOfSpareInstances() {
        return minimumNumberOfSpareInstances;
    }

    public MinimumNumberOfInstancesTimeRangeConfig getMinimumNumberOfInstancesTimeRangeConfig() {
        return minimumNumberOfInstancesTimeRangeConfig;
    }

    @DataBoundSetter
    public void setMinimumNumberOfInstancesTimeRangeConfig(
            MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig) {
        this.minimumNumberOfInstancesTimeRangeConfig = minimumNumberOfInstancesTimeRangeConfig;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public int getSpotBlockReservationDuration() {
        if (spotConfig == null) {
            return 0;
        }
        return spotConfig.getSpotBlockReservationDuration();
    }

    public String getSpotBlockReservationDurationStr() {
        if (spotConfig == null) {
            return "";
        } else {
            int dur = getSpotBlockReservationDuration();
            if (dur == 0) {
                return "";
            }
            return String.valueOf(getSpotBlockReservationDuration());
        }
    }

    public String getInstanceCapStr() {
        if (instanceCap == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(instanceCap);
        }
    }

    public String getSpotMaxBidPrice() {
        if (spotConfig == null) {
            return null;
        }
        return SpotConfiguration.normalizeBid(spotConfig.getSpotMaxBidPrice());
    }

    public String getIamInstanceProfile() {
        return iamInstanceProfile;
    }

    @DataBoundSetter
    public void setHostKeyVerificationStrategy(HostKeyVerificationStrategyEnum hostKeyVerificationStrategy) {
        this.hostKeyVerificationStrategy = (hostKeyVerificationStrategy != null)
                ? hostKeyVerificationStrategy
                : HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT;
    }

    @NonNull
    public HostKeyVerificationStrategyEnum getHostKeyVerificationStrategy() {
        return hostKeyVerificationStrategy != null
                ? hostKeyVerificationStrategy
                : HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT;
    }

    @CheckForNull
    public String getAmiOwners() {
        return amiOwners;
    }

    @DataBoundSetter
    public void setAmiOwners(String amiOwners) {
        this.amiOwners = amiOwners;
    }

    @CheckForNull
    public String getAmiUsers() {
        return amiUsers;
    }

    @DataBoundSetter
    public void setAmiUsers(String amiUsers) {
        this.amiUsers = amiUsers;
    }

    @CheckForNull
    public List<EC2Filter> getAmiFilters() {
        return amiFilters;
    }

    @DataBoundSetter
    public void setAmiFilters(List<EC2Filter> amiFilters) {
        this.amiFilters = amiFilters;
    }

    @DataBoundSetter
    public void setAvoidUsingOrphanedNodes(Boolean avoidUsingOrphanedNodes) {
        this.avoidUsingOrphanedNodes = avoidUsingOrphanedNodes;
    }

    @Override
    public String toString() {
        return "SlaveTemplate{" + "description='" + description + '\'' + ", labels='" + labels + '\'' + '}';
    }

    public int getMaxTotalUses() {
        return maxTotalUses;
    }

    public boolean isAvoidUsingOrphanedNodes() {
        return avoidUsingOrphanedNodes;
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

    public Tenancy getTenancyAttribute() {
        return tenancy;
    }

    public Boolean getEnclaveEnabled() {
        return enclaveEnabled;
    }

    public DescribableList<NodeProperty<?>, NodePropertyDescriptor> getNodeProperties() {
        return Objects.requireNonNull(nodeProperties);
    }

    public enum ProvisionOptions {
        ALLOW_CREATE,
        FORCE_CREATE
    }

    /**
     * Provisions a new EC2 agent or starts a previously stopped on-demand instance.
     *
     * @return always non-null. This needs to be then added to {@link Hudson#addNode(Node)}.
     */
    @NonNull
    public List<EC2AbstractSlave> provision(int number, EnumSet<ProvisionOptions> provisionOptions)
            throws SdkException, IOException {
        final Image image = getImage();
        if (this.spotConfig != null) {
            if (provisionOptions.contains(ProvisionOptions.ALLOW_CREATE)
                    || provisionOptions.contains(ProvisionOptions.FORCE_CREATE)) {
                return provisionSpot(image, number, provisionOptions);
            }
            return Collections.emptyList();
        }
        return provisionOndemand(image, number, provisionOptions);
    }

    /**
     * Safely we can pickup only instance that is not known by Jenkins at all.
     */
    private boolean checkInstance(Instance instance) {
        for (EC2AbstractSlave node : NodeIterator.nodes(EC2AbstractSlave.class)) {
            if ((node.getInstanceId().equals(instance.instanceId()))
                    && (!(instance.state().name().equals(InstanceStateName.STOPPED)))) {
                logInstanceCheck(
                        instance, ". false - found existing corresponding Jenkins agent: " + node.getInstanceId());
                return false;
            }
        }
        logInstanceCheck(instance, " true - Instance is not connected to Jenkins");
        return true;
    }

    private void logInstanceCheck(Instance instance, String message) {
        logProvisionInfo("checkInstance: " + instance.instanceId() + "." + message);
    }

    private boolean isSameIamInstanceProfile(Instance instance) {
        return StringUtils.isBlank(getIamInstanceProfile())
                || (instance.iamInstanceProfile() != null
                        && instance.iamInstanceProfile().arn().equals(getIamInstanceProfile()));
    }

    private boolean isTerminatingOrShuttindDown(InstanceStateName instanceStateName) {
        return instanceStateName.equals(InstanceStateName.TERMINATED)
                || instanceStateName.equals(InstanceStateName.SHUTTING_DOWN);
    }

    private void logProvisionInfo(String message) {
        LOGGER.info(this + ". " + message);
    }

    HashMap<RunInstancesRequest, List<Filter>> makeRunInstancesRequestAndFilters(Image image, int number, Ec2Client ec2)
            throws IOException {
        return makeRunInstancesRequestAndFilters(image, number, ec2, true);
    }

    @Deprecated
    HashMap<RunInstancesRequest, List<Filter>> makeRunInstancesRequestAndFilters(int number, Ec2Client ec2)
            throws IOException {
        return makeRunInstancesRequestAndFilters(getImage(), number, ec2);
    }

    HashMap<RunInstancesRequest, List<Filter>> makeRunInstancesRequestAndFilters(
            Image image, int number, Ec2Client ec2, boolean rotateSubnet) throws IOException {
        String imageId = image.imageId();
        RunInstancesRequest.Builder riRequestBuilder = RunInstancesRequest.builder()
                .imageId(image.imageId())
                .minCount(1)
                .maxCount(number)
                .instanceType(InstanceType.fromValue(type))
                .ebsOptimized(ebsOptimized)
                .monitoring(RunInstancesMonitoringEnabled.builder()
                        .enabled(monitoring)
                        .build());

        if (t2Unlimited) {
            CreditSpecificationRequest creditRequest =
                    CreditSpecificationRequest.builder().cpuCredits("unlimited").build();
            riRequestBuilder.creditSpecification(creditRequest);
        }

        riRequestBuilder.blockDeviceMappings(getBlockDeviceMappings(image));

        if (stopOnTerminate) {
            riRequestBuilder.instanceInitiatedShutdownBehavior(ShutdownBehavior.STOP);
            logProvisionInfo("Setting Instance Initiated Shutdown Behavior : ShutdownBehavior.Stop");
        } else {
            riRequestBuilder.instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE);
            logProvisionInfo("Setting Instance Initiated Shutdown Behavior : ShutdownBehavior.Terminate");
        }

        List<Filter> diFilters = new ArrayList<>();
        diFilters.add(Filter.builder().name("image-id").values(imageId).build());
        diFilters.add(Filter.builder().name("instance-type").values(type).build());

        KeyPair keyPair = getKeyPair(ec2);
        if (keyPair == null) {
            logProvisionInfo("Could not retrieve a valid key pair.");
            return null;
        }
        riRequestBuilder.userData(Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8)));
        riRequestBuilder.keyName(keyPair.getKeyPairInfo().keyName());
        diFilters.add(Filter.builder()
                .name("key-name")
                .values(keyPair.getKeyPairInfo().keyName())
                .build());

        Placement.Builder placementBuilder = Placement.builder();
        if (StringUtils.isNotBlank(getZone())) {
            if (getTenancyAttribute().equals(Tenancy.Dedicated)) {
                placementBuilder.tenancy("dedicated");
            }
            riRequestBuilder.placement(placementBuilder.build());
            diFilters.add(
                    Filter.builder().name("availability-zone").values(getZone()).build());
        }

        if (getTenancyAttribute().equals(Tenancy.Host)) {
            placementBuilder.tenancy("host");
            Placement placement = placementBuilder.build();
            riRequestBuilder.placement(placement);
            diFilters.add(Filter.builder()
                    .name("tenancy")
                    .values(placement.tenancyAsString())
                    .build());
        } else if (getTenancyAttribute().equals(Tenancy.Default)) {
            placementBuilder.tenancy("default");
            Placement placement = placementBuilder.build();
            riRequestBuilder.placement(placement);
            diFilters.add(Filter.builder()
                    .name("tenancy")
                    .values(placement.tenancyAsString())
                    .build());
        }

        String subnetId = chooseSubnetId(rotateSubnet);
        LOGGER.log(Level.FINE, () -> String.format("Chose subnetId %s", subnetId));

        InstanceNetworkInterfaceSpecification.Builder netBuilder = InstanceNetworkInterfaceSpecification.builder();
        if (StringUtils.isNotBlank(subnetId)) {
            netBuilder.subnetId(subnetId);

            diFilters.add(Filter.builder().name("subnet-id").values(subnetId).build());

            /*
             * If we have a subnet ID then we can only use VPC security groups
             */
            if (!getSecurityGroupSet().isEmpty()) {
                List<String> groupIds = getEc2SecurityGroups(ec2);

                if (!groupIds.isEmpty()) {
                    netBuilder.groups(groupIds);

                    diFilters.add(Filter.builder()
                            .name("instance.group-id")
                            .values(groupIds)
                            .build());
                }
            }
        } else {
            List<String> groupIds = getSecurityGroupsBy("group-name", securityGroupSet, ec2).securityGroups().stream()
                    .map(SecurityGroup::groupId)
                    .collect(Collectors.toList());
            netBuilder.groups(groupIds);

            if (!groupIds.isEmpty()) {
                diFilters.add(Filter.builder()
                        .name("instance.group-id")
                        .values(groupIds)
                        .build());
            }
        }

        switch (getAssociateIPStrategy()) {
            case PUBLIC_IP:
                netBuilder.associatePublicIpAddress(true);
                break;
            case PRIVATE_IP:
                netBuilder.associatePublicIpAddress(false);
                break;
            case SUBNET:
            case DEFAULT:
                break;
        }

        netBuilder.deviceIndex(0);
        riRequestBuilder.networkInterfaces(netBuilder.build());

        HashSet<Tag> instTags = buildTags(EC2Cloud.EC2_SLAVE_TYPE_DEMAND);
        for (Tag tag : instTags) {
            diFilters.add(Filter.builder()
                    .name("tag:" + tag.key())
                    .values(tag.value())
                    .build());
        }

        if (StringUtils.isNotBlank(getIamInstanceProfile())) {
            riRequestBuilder.iamInstanceProfile(IamInstanceProfileSpecification.builder()
                    .arn(getIamInstanceProfile())
                    .build());
        }

        List<TagSpecification> tagList = new ArrayList<>();
        tagList.add(TagSpecification.builder()
                .tags(instTags)
                .resourceType(ResourceType.INSTANCE)
                .build());
        tagList.add(TagSpecification.builder()
                .tags(instTags)
                .resourceType(ResourceType.VOLUME)
                .build());
        tagList.add(TagSpecification.builder()
                .tags(instTags)
                .resourceType(ResourceType.NETWORK_INTERFACE)
                .build());
        riRequestBuilder.tagSpecifications(tagList);

        if (metadataSupported) {
            InstanceMetadataOptionsRequest.Builder instanceMetadataOptionsRequestBuilder =
                    InstanceMetadataOptionsRequest.builder();
            instanceMetadataOptionsRequestBuilder.httpEndpoint(
                    metadataEndpointEnabled
                            ? InstanceMetadataEndpointState.ENABLED.toString()
                            : InstanceMetadataEndpointState.DISABLED.toString());
            instanceMetadataOptionsRequestBuilder.httpPutResponseHopLimit(
                    metadataHopsLimit == null ? EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT : metadataHopsLimit);
            instanceMetadataOptionsRequestBuilder.httpTokens(
                    metadataTokensRequired ? HttpTokensState.REQUIRED.toString() : HttpTokensState.OPTIONAL.toString());
            riRequestBuilder.metadataOptions(instanceMetadataOptionsRequestBuilder.build());
        }

        if (enclaveEnabled) {
            EnclaveOptionsRequest.Builder enclaveOptionsRequestBuilder =
                    EnclaveOptionsRequest.builder().enabled(true);
            riRequestBuilder.enclaveOptions(enclaveOptionsRequestBuilder.build());
        }

        HashMap<RunInstancesRequest, List<Filter>> ret = new HashMap<>();
        ret.put(riRequestBuilder.build(), diFilters);
        return ret;
    }

    @Deprecated
    HashMap<RunInstancesRequest, List<Filter>> makeRunInstancesRequestAndFilters(
            int number, Ec2Client ec2, boolean rotateSubnet) throws IOException {
        return makeRunInstancesRequestAndFilters(getImage(), number, ec2, rotateSubnet);
    }

    /**
     * Provisions an On-demand EC2 agent by launching a new instance or starting a previously-stopped instance.
     */
    private List<EC2AbstractSlave> provisionOndemand(
            Image image, int number, EnumSet<ProvisionOptions> provisionOptions) throws IOException {
        return provisionOndemand(image, number, provisionOptions, false, false);
    }

    /**
     * Provisions an On-demand EC2 agent by launching a new instance or starting a previously-stopped instance.
     */
    private List<EC2AbstractSlave> provisionOndemand(
            Image image,
            int number,
            EnumSet<ProvisionOptions> provisionOptions,
            boolean spotWithoutBidPrice,
            boolean fallbackSpotToOndemand)
            throws IOException {
        Ec2Client ec2 = getParent().connect();

        logProvisionInfo("Considering launching");
        HashMap<RunInstancesRequest, List<Filter>> runInstancesRequestFilterMap =
                makeRunInstancesRequestAndFilters(image, number, ec2);
        Map.Entry<RunInstancesRequest, List<Filter>> entry =
                runInstancesRequestFilterMap.entrySet().iterator().next();
        RunInstancesRequest riRequest = entry.getKey();
        List<Filter> diFilters = entry.getValue();

        DescribeInstancesRequest diRequest =
                DescribeInstancesRequest.builder().filters(diFilters).build();

        logProvisionInfo("Looking for existing instances with describe-instance: " + diRequest);

        DescribeInstancesResponse diResult = ec2.describeInstances(diRequest);
        List<Instance> orphansOrStopped = new ArrayList<>();
        if (!avoidUsingOrphanedNodes) {
            orphansOrStopped = findOrphansOrStopped(diResult, number);

            if (orphansOrStopped.isEmpty()
                    && !provisionOptions.contains(ProvisionOptions.FORCE_CREATE)
                    && !provisionOptions.contains(ProvisionOptions.ALLOW_CREATE)) {
                logProvisionInfo("No existing instance found - but cannot create new instance");
                return null;
            }

            wakeOrphansOrStoppedUp(ec2, orphansOrStopped);

            if (orphansOrStopped.size() == number) {
                return toSlaves(orphansOrStopped);
            }
        }

        RunInstancesRequest.Builder riRequestBuilder = riRequest.toBuilder();
        riRequestBuilder.maxCount(number - orphansOrStopped.size());

        List<Instance> newInstances;
        if (spotWithoutBidPrice) {
            InstanceMarketOptionsRequest.Builder instanceMarketOptionsRequestBuilder =
                    InstanceMarketOptionsRequest.builder().marketType(MarketType.SPOT);
            if (getSpotBlockReservationDuration() != 0) {
                SpotMarketOptions spotOptions = SpotMarketOptions.builder()
                        .blockDurationMinutes(getSpotBlockReservationDuration() * 60)
                        .build();
                instanceMarketOptionsRequestBuilder.spotOptions(spotOptions);
            }
            riRequestBuilder.instanceMarketOptions(instanceMarketOptionsRequestBuilder.build());
            RunInstancesRequest request = riRequestBuilder.build();
            try {
                // Record provisioning attempt
                recordProvisioningEvent(request, "REQUEST", null, 0);
                
                RunInstancesResponse response = ec2.runInstances(request);
                newInstances = new ArrayList<>(response.instances());
                
                // Record successful provisioning
                recordProvisioningEvent(request, "SUCCESS", null, newInstances.size());
            } catch (Ec2Exception e) {
                // Record failed provisioning
                recordProvisioningEvent(request, "FAILURE", e.getMessage(), 0);
                
                if (fallbackSpotToOndemand
                        && "InsufficientInstanceCapacity"
                                .equals(e.awsErrorDetails().errorCode())) {
                    logProvisionInfo(
                            "There is no spot capacity available matching your request, falling back to on-demand instance.");
                    riRequestBuilder.instanceMarketOptions(instanceMarketOptionsRequestBuilder.build());
                    
                    RunInstancesRequest fallbackRequest = riRequestBuilder.build();
                    // Record fallback attempt
                    recordProvisioningEvent(fallbackRequest, "REQUEST_FALLBACK", null, 0);
                    
                    RunInstancesResponse fallbackResponse = ec2.runInstances(fallbackRequest);
                    newInstances = new ArrayList<>(fallbackResponse.instances());
                    
                    // Record successful fallback provisioning
                    recordProvisioningEvent(fallbackRequest, "SUCCESS_FALLBACK", null, newInstances.size());
                } else {
                    throw e;
                }
            }
        } else {
            RunInstancesRequest request = riRequestBuilder.build();
            try {
                // Record provisioning attempt
                recordProvisioningEvent(request, "REQUEST", null, 0);
                
                RunInstancesResponse response = ec2.runInstances(request);
                newInstances = new ArrayList<>(response.instances());
                
                // Record successful provisioning
                recordProvisioningEvent(request, "SUCCESS", null, newInstances.size());
            } catch (Ec2Exception e) {
                // Record failed provisioning
                recordProvisioningEvent(request, "FAILURE", e.getMessage(), 0);
                
                logProvisionInfo("Jenkins attempted to reserve "
                        + riRequest.maxCount()
                        + " instances and received this EC2 exception: " + e.getMessage());
                throw e;
            }
        }
        // Have to create a new instance

        if (newInstances.isEmpty()) {
            logProvisionInfo("No new instances were created");
        }

        newInstances.addAll(orphansOrStopped);

        return toSlaves(newInstances);
    }

    void wakeOrphansOrStoppedUp(Ec2Client ec2, List<Instance> orphansOrStopped) {
        List<String> instances = new ArrayList<>();
        for (Instance instance : orphansOrStopped) {
            if (instance.state().name().equals(InstanceStateName.STOPPING)
                    || instance.state().name().equals(InstanceStateName.STOPPED)) {
                logProvisionInfo("Found stopped instances - will start it: " + instance);
                instances.add(instance.instanceId());
            } else {
                // Should be pending or running at this point, just let it come up
                logProvisionInfo(
                        "Found existing pending or running: " + instance.state().name() + " instance: " + instance);
            }
        }

        if (!instances.isEmpty()) {
            StartInstancesRequest siRequest =
                    StartInstancesRequest.builder().instanceIds(instances).build();
            StartInstancesResponse siResult = ec2.startInstances(siRequest);
            logProvisionInfo("Result of starting stopped instances:" + siResult);
        }
    }

    List<EC2AbstractSlave> toSlaves(List<Instance> newInstances) throws IOException {
        try {
            List<EC2AbstractSlave> slaves = new ArrayList<>(newInstances.size());
            for (Instance instance : newInstances) {
                slaves.add(newOndemandSlave(instance));
                logProvisionInfo("Return instance: " + instance);
            }
            return slaves;
        } catch (FormException e) {
            throw new AssertionError(e); // we should have discovered all
            // configuration issues upfront
        }
    }

    List<Instance> findOrphansOrStopped(DescribeInstancesResponse diResult, int number) {
        List<Instance> orphansOrStopped = new ArrayList<>();
        int count = 0;
        for (Reservation reservation : diResult.reservations()) {
            for (Instance instance : reservation.instances()) {
                if (!isSameIamInstanceProfile(instance)) {
                    logInstanceCheck(
                            instance,
                            ". false - IAM Instance profile does not match: " + instance.iamInstanceProfile());
                    continue;
                }

                if (isTerminatingOrShuttindDown(instance.state().name())) {
                    logInstanceCheck(instance, ". false - Instance is terminated or shutting down");
                    continue;
                }

                if (checkInstance(instance)) {
                    logProvisionInfo("Found existing instance: " + instance);
                    orphansOrStopped.add(instance);
                    count++;
                }

                if (count == number) {
                    return orphansOrStopped;
                }
            }
        }
        return orphansOrStopped;
    }

    private void setupRootDevice(Image image, List<BlockDeviceMapping> deviceMappings) {
        if (!DeviceType.EBS.equals(image.rootDeviceType())) {
            return;
        }

        // get the root device (only one expected in the blockmappings)
        if (deviceMappings.isEmpty()) {
            LOGGER.warning("AMI missing block devices");
            return;
        }
        BlockDeviceMapping rootMapping = deviceMappings.get(0);
        LOGGER.info("AMI had " + rootMapping.deviceName());
        LOGGER.info(rootMapping.ebs().toString());

        // Create a new AMI mapping as a copy of the existing one
        BlockDeviceMapping.Builder newRootMappingBuilder = rootMapping.toBuilder();
        EbsBlockDevice.Builder newRootDeviceBuilder = rootMapping.ebs().toBuilder();

        if (deleteRootOnTermination) {
            newRootDeviceBuilder.deleteOnTermination(Boolean.TRUE);
            // Check if the root device is already in the mapping and update it
            for (final BlockDeviceMapping mapping : image.blockDeviceMappings()) {
                LOGGER.info("Request had " + mapping.deviceName());
                if (rootMapping.deviceName().equals(mapping.deviceName())) {
                    // Existing mapping found, replace with the copy
                    newRootMappingBuilder.ebs(newRootDeviceBuilder.build());
                    deviceMappings.remove(0);
                    deviceMappings.add(0, newRootMappingBuilder.build());
                }
            }
        }

        // New existing mapping found, add a new one as the root
        newRootDeviceBuilder.encrypted(ebsEncryptRootVolume.getValue());
        String message = String.format(
                "EBS default encryption value set to: %s (%s)",
                ebsEncryptRootVolume.getDisplayText(), ebsEncryptRootVolume.getValue());
        logProvisionInfo(message);
        newRootMappingBuilder.ebs(newRootDeviceBuilder.build());
        deviceMappings.add(0, newRootMappingBuilder.build());
    }

    private List<BlockDeviceMapping> getNewEphemeralDeviceMapping(Image image) {

        final List<BlockDeviceMapping> oldDeviceMapping = image.blockDeviceMappings();

        final Set<String> occupiedDevices = new HashSet<>();
        for (final BlockDeviceMapping mapping : oldDeviceMapping) {

            occupiedDevices.add(mapping.deviceName());
        }

        final List<String> available =
                new ArrayList<>(Arrays.asList("ephemeral0", "ephemeral1", "ephemeral2", "ephemeral3"));

        final List<BlockDeviceMapping> newDeviceMapping = new ArrayList<>(4);
        for (char suffix = 'b'; suffix <= 'z' && !available.isEmpty(); suffix++) {

            final String deviceName = String.format("/dev/xvd%s", suffix);

            if (occupiedDevices.contains(deviceName)) {
                continue;
            }

            final BlockDeviceMapping newMapping = BlockDeviceMapping.builder()
                    .deviceName(deviceName)
                    .virtualName(available.get(0))
                    .build();

            newDeviceMapping.add(newMapping);
            available.remove(0);
        }

        return newDeviceMapping;
    }

    private void setupEphemeralDeviceMapping(Image image, List<BlockDeviceMapping> deviceMappings) {
        // Don't wipe out pre-existing mappings
        deviceMappings.addAll(getNewEphemeralDeviceMapping(image));
    }

    @NonNull
    private static List<String> makeImageAttributeList(@CheckForNull String attr) {
        return Stream.of(Util.tokenize(Util.fixNull(attr))).collect(Collectors.toList());
    }

    @NonNull
    private DescribeImagesRequest makeDescribeImagesRequest() throws SdkException {
        List<String> imageIds =
                Util.fixEmptyAndTrim(ami) == null ? Collections.emptyList() : Collections.singletonList(ami);
        List<String> owners = makeImageAttributeList(amiOwners);
        List<String> users = makeImageAttributeList(amiUsers);
        List<Filter> filters = EC2Filter.toFilterList(amiFilters);

        // Raise an exception if there were no search attributes.
        // This is legal but not what anyone wants - it will
        // launch random recently created public AMIs.
        int numAttrs =
                Stream.of(imageIds, owners, users, filters).mapToInt(List::size).sum();
        if (numAttrs == 0) {
            throw SdkException.builder()
                    .message("Neither AMI ID nor AMI search attributes provided")
                    .build();
        }

        return DescribeImagesRequest.builder()
                .imageIds(imageIds)
                .owners(owners)
                .executableUsers(users)
                .filters(filters)
                .build();
    }

    @NonNull
    private Image getImage() throws SdkException {
        DescribeImagesRequest request = makeDescribeImagesRequest();

        LOGGER.info("Getting image for request " + request);
        List<Image> images =
                new ArrayList<>(getParent().connect().describeImages(request).images());
        if (images.isEmpty()) {
            throw SdkException.builder()
                    .message("Unable to find image for request " + request)
                    .build();
        }

        // Sort in reverse by creation date to get latest image
        images.sort(Comparator.comparing(Image::creationDate).reversed());
        return images.get(0);
    }

    private void setupCustomDeviceMapping(List<BlockDeviceMapping> deviceMappings) {
        if (StringUtils.isNotBlank(customDeviceMapping)) {
            deviceMappings.addAll(DeviceMappingParser.parse(customDeviceMapping));
        }
    }

    /**
     * Provision a new agent for an EC2 spot instance to call back to Jenkins
     */
    private List<EC2AbstractSlave> provisionSpot(Image image, int number, EnumSet<ProvisionOptions> provisionOptions)
            throws IOException {
        if (!spotConfig.useBidPrice) {
            return provisionOndemand(image, 1, provisionOptions, true, spotConfig.getFallbackToOndemand());
        }

        Ec2Client ec2 = getParent().connect();
        String imageId = image.imageId();

        try {
            LOGGER.info("Launching " + imageId + " for template " + description);

            KeyPair keyPair = getKeyPair(ec2);

            RequestSpotInstancesRequest.Builder spotRequestBuilder = RequestSpotInstancesRequest.builder();

            // Validate spot bid before making the request
            if (getSpotMaxBidPrice() == null) {
                throw SdkException.builder()
                        .message("Invalid Spot price specified: " + getSpotMaxBidPrice())
                        .build();
            }

            spotRequestBuilder.spotPrice(getSpotMaxBidPrice());
            spotRequestBuilder.instanceCount(number);

            RequestSpotLaunchSpecification.Builder launchSpecificationBuilder =
                    RequestSpotLaunchSpecification.builder();

            launchSpecificationBuilder.imageId(imageId);
            launchSpecificationBuilder.instanceType(InstanceType.fromValue(type));
            launchSpecificationBuilder.ebsOptimized(ebsOptimized);
            launchSpecificationBuilder.monitoring(
                    RunInstancesMonitoringEnabled.builder().enabled(monitoring).build());

            if (StringUtils.isNotBlank(getZone())) {
                SpotPlacement placement =
                        SpotPlacement.builder().availabilityZone(getZone()).build();
                launchSpecificationBuilder.placement(placement);
            }

            InstanceNetworkInterfaceSpecification.Builder netBuilder = InstanceNetworkInterfaceSpecification.builder();
            String subnetId = chooseSubnetId();
            LOGGER.log(Level.FINE, () -> String.format("Chose subnetId %s", subnetId));
            if (StringUtils.isNotBlank(subnetId)) {
                netBuilder.subnetId(subnetId);

                /*
                 * If we have a subnet ID then we can only use VPC security groups
                 */
                if (!securityGroupSet.isEmpty()) {
                    List<String> groupIds = getEc2SecurityGroups(ec2);
                    if (!groupIds.isEmpty()) {
                        netBuilder.groups(groupIds);
                    }
                }
            } else {
                if (!securityGroupSet.isEmpty()) {
                    List<String> groupIds =
                            getSecurityGroupsBy("group-name", securityGroupSet, ec2).securityGroups().stream()
                                    .map(SecurityGroup::groupId)
                                    .collect(Collectors.toList());
                    netBuilder.groups(groupIds);
                }
            }

            String userDataString = Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));

            launchSpecificationBuilder.userData(userDataString);
            launchSpecificationBuilder.keyName(keyPair.getKeyPairInfo().keyName());
            launchSpecificationBuilder.instanceType(InstanceType.fromValue(type));

            switch (getAssociateIPStrategy()) {
                case PUBLIC_IP:
                    netBuilder.associatePublicIpAddress(true);
                    break;
                case PRIVATE_IP:
                case DEFAULT:
                    netBuilder.associatePublicIpAddress(false);
                    break;
                case SUBNET:
                    break;
            }

            netBuilder.deviceIndex(0);
            launchSpecificationBuilder.networkInterfaces(netBuilder.build());

            HashSet<Tag> instTags = buildTags(EC2Cloud.EC2_SLAVE_TYPE_SPOT);

            if (StringUtils.isNotBlank(getIamInstanceProfile())) {
                launchSpecificationBuilder.iamInstanceProfile(IamInstanceProfileSpecification.builder()
                        .arn(getIamInstanceProfile())
                        .build());
            }

            launchSpecificationBuilder.blockDeviceMappings(getBlockDeviceMappings(image));

            spotRequestBuilder.launchSpecification(launchSpecificationBuilder.build());

            if (getSpotBlockReservationDuration() != 0) {
                spotRequestBuilder.blockDurationMinutes(getSpotBlockReservationDuration() * 60);
            }

            RequestSpotInstancesResponse reqResult;
            try {
                RequestSpotInstancesRequest spotRequest = spotRequestBuilder.build();
                // Record spot provisioning attempt
                recordSpotProvisioningEvent(spotRequest, "REQUEST", null, 0);
                
                // Make the request for a new Spot instance
                reqResult = ec2.requestSpotInstances(spotRequest);
                
                // Record successful spot provisioning
                recordSpotProvisioningEvent(spotRequest, "SUCCESS", null, reqResult.spotInstanceRequests().size());
            } catch (Ec2Exception e) {
                RequestSpotInstancesRequest spotRequest = spotRequestBuilder.build();
                // Record failed spot provisioning
                recordSpotProvisioningEvent(spotRequest, "FAILURE", e.getMessage(), 0);
                
                if (spotConfig.getFallbackToOndemand()
                        && "MaxSpotInstanceCountExceeded"
                                .equals(e.awsErrorDetails().errorCode())) {
                    logProvisionInfo(
                            "There is no spot capacity available matching your request, falling back to on-demand instance.");
                    return provisionOndemand(image, number, provisionOptions);
                } else {
                    throw e;
                }
            }

            List<SpotInstanceRequest> reqInstances = reqResult.spotInstanceRequests();
            if (reqInstances.isEmpty()) {
                throw SdkException.builder().message("No spot instances found").build();
            }

            List<EC2AbstractSlave> slaves = new ArrayList<>(reqInstances.size());
            for (SpotInstanceRequest spotInstReq : reqInstances) {
                if (spotInstReq == null) {
                    throw SdkException.builder()
                            .message("Spot instance request is null")
                            .build();
                }
                String slaveName = spotInstReq.spotInstanceRequestId();

                if (spotConfig.getFallbackToOndemand()) {
                    for (int i = 0; i < 2 && spotInstReq.status().code().equals("pending-evaluation"); i++) {
                        LOGGER.info("Spot request " + slaveName + " is still pending evaluation");
                        Thread.sleep(5000);
                        LOGGER.info("Fetching info about spot request " + slaveName);
                        DescribeSpotInstanceRequestsRequest describeRequest =
                                DescribeSpotInstanceRequestsRequest.builder()
                                        .spotInstanceRequestIds(slaveName)
                                        .build();
                        spotInstReq = ec2.describeSpotInstanceRequests(describeRequest)
                                .spotInstanceRequests()
                                .get(0);
                    }

                    List<String> spotRequestBadCodes =
                            Arrays.asList("capacity-not-available", "capacity-oversubscribed", "price-too-low");
                    if (spotRequestBadCodes.contains(spotInstReq.status().code())) {
                        LOGGER.info(
                                "There is no spot capacity available matching your request, falling back to on-demand instance.");
                        List<String> requestsToCancel = reqInstances.stream()
                                .map(SpotInstanceRequest::spotInstanceRequestId)
                                .collect(Collectors.toList());
                        CancelSpotInstanceRequestsRequest cancelRequest = CancelSpotInstanceRequestsRequest.builder()
                                .spotInstanceRequestIds(requestsToCancel)
                                .build();
                        ec2.cancelSpotInstanceRequests(cancelRequest);
                        return provisionOndemand(image, number, provisionOptions);
                    }
                }

                // Now that we have our Spot request, we can set tags on it
                updateRemoteTags(
                        ec2, instTags, "InvalidSpotInstanceRequestID.NotFound", spotInstReq.spotInstanceRequestId());

                // That was a remote request - we should also update our local instance data
                SpotInstanceRequest.Builder spotInstReqBuilder = spotInstReq.toBuilder();
                spotInstReqBuilder.tags(instTags);

                LOGGER.info("Spot instance id in provision: " + spotInstReq.spotInstanceRequestId());

                slaves.add(newSpotSlave(spotInstReqBuilder.build()));
            }

            return slaves;

        } catch (FormException e) {
            throw new AssertionError(); // we should have discovered all
            // configuration issues upfront
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private List<BlockDeviceMapping> getBlockDeviceMappings(Image image) {
        List<BlockDeviceMapping> newMappings = new ArrayList<>(image.blockDeviceMappings());

        setupRootDevice(image, newMappings);

        if (useEphemeralDevices) {
            newMappings.addAll(getNewEphemeralDeviceMapping(image));
        } else {
            if (StringUtils.isNotBlank(customDeviceMapping)) {
                newMappings.addAll(DeviceMappingParser.parse(customDeviceMapping));
            }
        }
        return newMappings;
    }

    private HashSet<Tag> buildTags(String slaveType) {
        boolean hasCustomTypeTag = false;
        boolean hasJenkinsServerUrlTag = false;
        HashSet<Tag> instTags = new HashSet<>();
        if (tags != null && !tags.isEmpty()) {
            for (EC2Tag t : tags) {
                instTags.add(Tag.builder().key(t.getName()).value(t.getValue()).build());
                if (StringUtils.equals(t.getName(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)) {
                    hasCustomTypeTag = true;
                }
                if (StringUtils.equals(t.getName(), EC2Tag.TAG_NAME_JENKINS_SERVER_URL)) {
                    hasJenkinsServerUrlTag = true;
                }
            }
        }
        if (!hasCustomTypeTag) {
            instTags.add(Tag.builder()
                    .key(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)
                    .value(EC2Cloud.getSlaveTypeTagValue(slaveType, description))
                    .build());
        }
        JenkinsLocationConfiguration jenkinsLocation = JenkinsLocationConfiguration.get();
        if (!hasJenkinsServerUrlTag && jenkinsLocation.getUrl() != null) {
            instTags.add(Tag.builder()
                    .key(EC2Tag.TAG_NAME_JENKINS_SERVER_URL)
                    .value(jenkinsLocation.getUrl())
                    .build());
        }

        if (parent != null && StringUtils.isNotBlank(parent.name)) {
            instTags.add(Tag.builder()
                    .key(EC2Tag.TAG_NAME_JENKINS_CLOUD_NAME)
                    .value(parent.name)
                    .build());
        }

        return instTags;
    }

    protected EC2OndemandSlave newOndemandSlave(Instance inst) throws FormException, IOException {
        EC2AgentConfig.OnDemand config = new EC2AgentConfig.OnDemandBuilder()
                .withName(getSlaveName(inst.instanceId()))
                .withInstanceId(inst.instanceId())
                .withDescription(description)
                .withRemoteFS(remoteFS)
                .withNumExecutors(getNumExecutors())
                .withLabelString(labels)
                .withMode(mode)
                .withInitScript(initScript)
                .withTmpDir(tmpDir)
                .withNodeProperties(nodeProperties.toList())
                .withRemoteAdmin(remoteAdmin)
                .withJavaPath(javaPath)
                .withJvmopts(jvmopts)
                .withStopOnTerminate(stopOnTerminate)
                .withIdleTerminationMinutes(idleTerminationMinutes)
                .withPublicDNS(inst.publicDnsName())
                .withPrivateDNS(inst.privateDnsName())
                .withTags(EC2Tag.fromAmazonTags(inst.tags()))
                .withCloudName(parent.name)
                .withLaunchTimeout(getLaunchTimeout())
                .withAmiType(amiType)
                .withConnectionStrategy(connectionStrategy)
                .withMaxTotalUses(maxTotalUses)
                .withTenancyAttribute(tenancy)
                .withMetadataSupported(metadataSupported)
                .withMetadataEndpointEnabled(metadataEndpointEnabled)
                .withMetadataTokensRequired(metadataTokensRequired)
                .withMetadataHopsLimit(metadataHopsLimit)
                .build();
        return EC2AgentFactory.getInstance().createOnDemandAgent(config);
    }

    protected EC2SpotSlave newSpotSlave(SpotInstanceRequest sir) throws FormException, IOException {
        EC2AgentConfig.Spot config = new EC2AgentConfig.SpotBuilder()
                .withName(getSlaveName(sir.spotInstanceRequestId()))
                .withSpotInstanceRequestId(sir.spotInstanceRequestId())
                .withDescription(description)
                .withRemoteFS(remoteFS)
                .withNumExecutors(getNumExecutors())
                .withMode(mode)
                .withInitScript(initScript)
                .withTmpDir(tmpDir)
                .withLabelString(labels)
                .withNodeProperties(nodeProperties.toList())
                .withRemoteAdmin(remoteAdmin)
                .withJavaPath(javaPath)
                .withJvmopts(jvmopts)
                .withIdleTerminationMinutes(idleTerminationMinutes)
                .withTags(EC2Tag.fromAmazonTags(sir.tags()))
                .withCloudName(parent.name)
                .withLaunchTimeout(getLaunchTimeout())
                .withAmiType(amiType)
                .withConnectionStrategy(connectionStrategy)
                .withMaxTotalUses(maxTotalUses)
                .build();
        return EC2AgentFactory.getInstance().createSpotAgent(config);
    }

    /**
     * Get a KeyPair from the configured information for the agent template
     */
    @CheckForNull
    private KeyPair getKeyPair(Ec2Client ec2) throws IOException, SdkException {
        EC2PrivateKey ec2PrivateKey = getParent().resolvePrivateKey();
        if (ec2PrivateKey == null) {
            throw SdkException.builder()
                    .message("No keypair credential found. Please configure a credential in the Jenkins configuration.")
                    .build();
        }
        KeyPair keyPair = ec2PrivateKey.find(ec2);
        if (keyPair == null) {
            throw SdkException.builder()
                    .message("No matching keypair found on EC2. Is the EC2 private key a valid one?")
                    .build();
        }
        LOGGER.fine("found matching keypair");
        return keyPair;
    }

    /**
     * Update the tags stored in EC2 with the specified information. Re-try 5 times if instances isn't up by
     * catchErrorCode - e.g. InvalidSpotInstanceRequestID.NotFound or InvalidInstanceRequestID.NotFound
     *
     * @param ec2
     * @param instTags
     * @param catchErrorCode
     * @param params
     * @throws InterruptedException
     */
    private void updateRemoteTags(
            Ec2Client ec2, Collection<Tag> instTags, @NonNull String catchErrorCode, String... params)
            throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            try {
                ec2.createTags(CreateTagsRequest.builder()
                        .resources(params)
                        .tags(instTags)
                        .build());
                break;
            } catch (AwsServiceException e) {
                if (catchErrorCode.equals(e.awsErrorDetails().errorCode())) {
                    Thread.sleep(5000);
                    continue;
                }
                LOGGER.log(Level.SEVERE, e.awsErrorDetails().errorMessage(), e);
            }
        }
    }

    /**
     * Get a list of security group ids for the agent
     */
    private List<String> getEc2SecurityGroups(Ec2Client ec2) throws SdkException {
        LOGGER.log(
                Level.FINE,
                () -> String.format(
                        "Get security group %s for EC2Cloud %s with currentSubnetId %s",
                        securityGroupSet, this.getParent().name, getCurrentSubnetId()));
        List<String> groupIds = new ArrayList<>();
        DescribeSecurityGroupsResponse groupResult = getSecurityGroupsBy("group-name", securityGroupSet, ec2);
        if (groupResult.securityGroups().isEmpty()) {
            groupResult = getSecurityGroupsBy("group-id", securityGroupSet, ec2);
        }

        for (SecurityGroup group : groupResult.securityGroups()) {
            LOGGER.log(
                    Level.FINE,
                    () -> String.format(
                            "Checking security group %s (vpc-id = %s, subnet-id = %s)",
                            group.groupId(), group.vpcId(), getCurrentSubnetId()));
            if (group.vpcId() != null && !group.vpcId().isEmpty()) {
                List<Filter> filters = new ArrayList<>();
                filters.add(
                        Filter.builder().name("vpc-id").values(group.vpcId()).build());
                filters.add(Filter.builder().name("state").values("available").build());
                filters.add(Filter.builder()
                        .name("subnet-id")
                        .values(getCurrentSubnetId())
                        .build());

                DescribeSubnetsResponse subnetResult = ec2.describeSubnets(
                        DescribeSubnetsRequest.builder().filters(filters).build());

                List<Subnet> subnets = subnetResult.subnets();
                if (subnets != null && !subnets.isEmpty()) {
                    LOGGER.log(Level.FINE, () -> "Adding security group");
                    groupIds.add(group.groupId());
                }
            }
        }

        if (securityGroupSet.size() != groupIds.size()) {
            throw SdkException.builder()
                    .message("Security groups must all be VPC security groups to work in a VPC context")
                    .build();
        }

        return groupIds;
    }

    private DescribeSecurityGroupsResponse getSecurityGroupsBy(
            String filterName, Set<String> filterValues, Ec2Client ec2) {
        DescribeSecurityGroupsRequest groupReq = DescribeSecurityGroupsRequest.builder()
                .filters(Filter.builder().name(filterName).values(filterValues).build())
                .build();
        return ec2.describeSecurityGroups(groupReq);
    }

    /**
     * Provisions a new EC2 agent based on the currently running instance on EC2, instead of starting a new one.
     */
    public EC2AbstractSlave attach(String instanceId, TaskListener listener) throws SdkException, IOException {
        PrintStream logger = listener.getLogger();
        Ec2Client ec2 = getParent().connect();

        try {
            logger.println("Attaching to " + instanceId);
            LOGGER.info("Attaching to " + instanceId);
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .instanceIds(Collections.singletonList(instanceId))
                    .build();
            Instance inst = ec2.describeInstances(request)
                    .reservations()
                    .get(0)
                    .instances()
                    .get(0);
            return newOndemandSlave(inst);
        } catch (FormException e) {
            throw new AssertionError(); // we should have discovered all
            // configuration issues upfront
        }
    }

    /**
     * Initializes data structure that we don't persist.
     */
    protected Object readResolve() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            j.checkPermission(Jenkins.ADMINISTER);
        }

        securityGroupSet = parseSecurityGroups();

        /**
         * In releases of this plugin prior to 1.18, template-specific instance caps could be configured but were not
         * enforced. As a result, it was possible to have the instance cap for a template be configured to 0 (zero) with
         * no ill effects. Starting with version 1.18, template-specific instance caps are enforced, so if a
         * configuration has a cap of zero for a template, no instances will be launched from that template. Since there
         * is no practical value of intentionally setting the cap to zero, this block will override such a setting to a
         * value that means 'no cap'.
         */
        if (instanceCap == 0) {
            instanceCap = Integer.MAX_VALUE;
        }

        if (amiType == null) {
            amiType = new UnixData(rootCommandPrefix, slaveCommandPrefix, slaveCommandSuffix, sshPort, null);
        }

        if (type != null && !type.isEmpty()) {
            type = InstanceTypeCompat.of(type).toString();
        }

        if (associateIPStrategy == null) {
            associateIPStrategy = AssociateIPStrategy.backwardsCompatible(associatePublicIp);
        }

        // 1.43 new parameters
        if (connectionStrategy == null) {
            connectionStrategy = ConnectionStrategy.backwardsCompatible(
                    usePrivateDnsName, connectUsingPublicIp, AssociateIPStrategy.PUBLIC_IP == associateIPStrategy);
        }

        if (maxTotalUses == 0) {
            maxTotalUses = -1;
        }

        if (nodeProperties == null) {
            nodeProperties = new DescribableList<>(Saveable.NOOP);
        }

        if (tenancy == null) {
            tenancy = Tenancy.Default;
        }

        // migration of old value to new variable.
        if (useDedicatedTenancy) {
            tenancy = Tenancy.Dedicated;
        }

        if (ebsEncryptRootVolume == null) {
            ebsEncryptRootVolume = EbsEncryptRootVolume.DEFAULT;
        }

        if (metadataSupported == null) {
            metadataSupported = EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED;
        }
        if (metadataEndpointEnabled == null) {
            metadataEndpointEnabled = EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED;
        }
        if (metadataTokensRequired == null) {
            metadataTokensRequired = EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED;
        }
        if (metadataHopsLimit == null) {
            metadataHopsLimit = EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT;
        }
        if (StringUtils.isBlank(javaPath)) {
            javaPath = EC2AbstractSlave.DEFAULT_JAVA_PATH;
        }
        if (enclaveEnabled == null) {
            enclaveEnabled = EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED;
        }

        return this;
    }

    @Override
    public Descriptor<SlaveTemplate> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
    }

    public int getLaunchTimeout() {
        return launchTimeout <= 0 ? Integer.MAX_VALUE : launchTimeout;
    }

    public String getLaunchTimeoutStr() {
        if (launchTimeout == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(launchTimeout);
        }
    }

    public boolean isWindowsSlave() {
        return amiType.isWindows();
    }

    public boolean isUnixSlave() {
        return amiType.isUnix();
    }

    public boolean isMacAgent() {
        return amiType.isMac();
    }

    public boolean isSSHAgent() {
        return amiType.isSSHAgent();
    }

    public boolean isWinRMAgent() {
        return amiType.isWinRMAgent();
    }

    public Secret getAdminPassword() {
        return amiType.isWinRMAgent() ? ((WindowsData) amiType).getPassword() : Secret.fromString("");
    }

    public boolean isUseHTTPS() {
        return amiType.isWinRMAgent() && ((WindowsData) amiType).isUseHTTPS();
    }

    /**
     *
     * @param ec2
     * @param allSubnets if true, uses all subnets defined for this SlaveTemplate as the filter, else will only use the current subnet
     * @return DescribeInstancesResult of DescribeInstanceRequst constructed from this SlaveTemplate's configs
     */
    DescribeInstancesResponse getDescribeInstanceResult(Ec2Client ec2, boolean allSubnets) throws IOException {
        HashMap<RunInstancesRequest, List<Filter>> runInstancesRequestFilterMap =
                makeRunInstancesRequestAndFilters(getImage(), 1, ec2, false);
        Map.Entry<RunInstancesRequest, List<Filter>> entry =
                runInstancesRequestFilterMap.entrySet().iterator().next();
        List<Filter> diFilters = entry.getValue();

        if (allSubnets) {
            /* remove any existing subnet-id filters */
            List<Filter> rmvFilters = new ArrayList<>();
            for (Filter f : diFilters) {
                if (f.name().equals("subnet-id")) {
                    rmvFilters.add(f);
                }
            }
            for (Filter f : rmvFilters) {
                diFilters.remove(f);
            }

            /* Add filter using all subnets defined for this SlaveTemplate */
            Filter subnetFilter = Filter.builder()
                    .name("subnet-id")
                    .values(Arrays.asList(getSubnetId().split(EC2_RESOURCE_ID_DELIMETERS)))
                    .build();
            diFilters.add(subnetFilter);
        }

        DescribeInstancesRequest diRequest =
                DescribeInstancesRequest.builder().filters(diFilters).build();
        return ec2.describeInstances(diRequest);
    }

    public boolean isAllowSelfSignedCertificate() {
        return amiType.isWinRMAgent() && ((WindowsData) amiType).isAllowSelfSignedCertificate();
    }

    @Extension
    public static final class OnSaveListener extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof Jenkins) {
                MinimumInstanceChecker.checkForMinimumInstances();
            }
        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {

        @Override
        public String getDisplayName() {
            return "";
        }

        public List<Descriptor<AMITypeData>> getAMITypeDescriptors() {
            return Jenkins.get().getDescriptorList(AMITypeData.class);
        }

        /**
         * Since this shares much of the configuration with {@link EC2Computer}, check its help page, too.
         */
        @Override
        public String getHelpFile(String fieldName) {
            String p = super.getHelpFile(fieldName);
            if (p != null) {
                return p;
            }
            Descriptor slaveDescriptor = Jenkins.get().getDescriptor(EC2OndemandSlave.class);
            if (slaveDescriptor != null) {
                p = slaveDescriptor.getHelpFile(fieldName);
                if (p != null) {
                    return p;
                }
            }
            slaveDescriptor = Jenkins.get().getDescriptor(EC2SpotSlave.class);
            if (slaveDescriptor != null) {
                return slaveDescriptor.getHelpFile(fieldName);
            }
            return null;
        }

        @Restricted(NoExternalUse.class)
        @POST
        public FormValidation doCheckDescription(@QueryParameter String value) {
            try {
                Jenkins.checkGoodName(value);
                return FormValidation.ok();
            } catch (Failure e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @RequirePOST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doValidateType(@QueryParameter String value) {
            InstanceType instanceType = InstanceType.fromValue(value);

            if (instanceType == InstanceType.UNKNOWN_TO_SDK_VERSION) {
                return FormValidation.error("Instance type unknown to SDK version");
            }

            return FormValidation.ok();
        }

        /***
         * Check that the AMI requested is available in the cloud and can be used.
         */
        @RequirePOST
        public FormValidation doValidateAmi(
                @QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId,
                @QueryParameter String region,
                @QueryParameter String altEC2Endpoint,
                final @QueryParameter String ami,
                @QueryParameter String roleArn,
                @QueryParameter String roleSessionName)
                throws IOException {
            checkPermission(EC2Cloud.PROVISION);
            AwsCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(
                    useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
            Ec2Client ec2 = AmazonEC2Factory.getInstance()
                    .connect(credentialsProvider, EC2Cloud.parseRegion(region), EC2Cloud.parseEndpoint(altEC2Endpoint));
            try {
                Image img = CloudHelper.getAmiImage(ec2, ami);
                if (img == null) {
                    return FormValidation.error("No such AMI, or not usable with this accessId: " + ami);
                }
                String ownerAlias = img.imageOwnerAlias();
                return FormValidation.ok(img.imageLocation() + (ownerAlias != null ? " by " + ownerAlias : ""));
            } catch (SdkException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        private void checkPermission(Permission p) {
            final EC2Cloud ancestorObject = Stapler.getCurrentRequest2().findAncestorObject(EC2Cloud.class);
            if (ancestorObject != null) {
                ancestorObject.checkPermission(p);
            } else {
                Jenkins.get().checkPermission(p);
            }
        }

        @POST
        public FormValidation doCheckLabelString(@QueryParameter String value, @QueryParameter Node.Mode mode) {
            if (mode == Node.Mode.EXCLUSIVE && (value == null || value.trim().isEmpty())) {
                return FormValidation.warning("You may want to assign labels to this node;"
                        + " it's marked to only run jobs that are exclusively tied to itself or a label.");
            }

            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckIdleTerminationMinutes(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }
            try {
                int val = Integer.parseInt(value);
                if (val >= -59) {
                    return FormValidation.ok();
                }
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("Idle Termination time must be a greater than -59 (or null)");
        }

        @POST
        public FormValidation doCheckMaxTotalUses(@QueryParameter String value) {
            try {
                int val = Integer.parseInt(value);
                if (val >= -1) {
                    return FormValidation.ok();
                }
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("Maximum Total Uses must be greater or equal to -1");
        }

        @POST
        public FormValidation doCheckMinimumNumberOfInstances(
                @QueryParameter String value, @QueryParameter String instanceCapStr) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }
            try {
                int val = Integer.parseInt(value);
                if (val >= 0) {
                    int instanceCap;
                    try {
                        instanceCap = Integer.parseInt(instanceCapStr);
                    } catch (NumberFormatException ignore) {
                        instanceCap = Integer.MAX_VALUE;
                    }
                    if (val > instanceCap) {
                        return FormValidation.error(
                                "Minimum number of instances must not be larger than AMI Instance Cap %d", instanceCap);
                    }
                    return FormValidation.ok();
                }
            } catch (NumberFormatException ignore) {
            }
            return FormValidation.error("Minimum number of instances must be a non-negative integer (or null)");
        }

        @POST
        public FormValidation doCheckMinimumNoInstancesActiveTimeRangeFrom(@QueryParameter String value) {
            try {
                MinimumNumberOfInstancesTimeRangeConfig.validateLocalTimeString(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Please enter value in format 'h:mm a' or 'HH:mm'");
            }
        }

        @POST
        public FormValidation doCheckMinimumNoInstancesActiveTimeRangeTo(@QueryParameter String value) {
            try {
                MinimumNumberOfInstancesTimeRangeConfig.validateLocalTimeString(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Please enter value in format 'h:mm a' or 'HH:mm'");
            }
        }

        // For some reason, all days will validate against this method so no need to repeat for each day.
        @POST
        public FormValidation doCheckMonday(
                @QueryParameter boolean monday,
                @QueryParameter boolean tuesday,
                @QueryParameter boolean wednesday,
                @QueryParameter boolean thursday,
                @QueryParameter boolean friday,
                @QueryParameter boolean saturday,
                @QueryParameter boolean sunday) {
            if (!(monday || tuesday || wednesday || thursday || friday || saturday || sunday)) {
                return FormValidation.warning(
                        "At least one day should be checked or minimum number of instances won't be active");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckMinimumNumberOfSpareInstances(
                @QueryParameter String value, @QueryParameter String instanceCapStr) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }
            try {
                int val = Integer.parseInt(value);
                if (val >= 0) {
                    int instanceCap;
                    try {
                        instanceCap = Integer.parseInt(instanceCapStr);
                    } catch (NumberFormatException ignore) {
                        instanceCap = Integer.MAX_VALUE;
                    }
                    if (val > instanceCap) {
                        return FormValidation.error(
                                "Minimum number of spare instances must not be larger than AMI Instance Cap %d",
                                instanceCap);
                    }
                    return FormValidation.ok();
                }
            } catch (NumberFormatException ignore) {
            }
            return FormValidation.error("Minimum number of spare instances must be a non-negative integer (or null)");
        }

        @POST
        public FormValidation doCheckInstanceCapStr(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }
            try {
                int val = Integer.parseInt(value);
                if (val > 0) {
                    return FormValidation.ok();
                }
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("InstanceCap must be a non-negative integer (or null)");
        }

        /*
         * Validate the Spot Block Duration to be between 0 & 6 hours as specified in the AWS API
         */
        @POST
        public FormValidation doCheckSpotBlockReservationDurationStr(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }
            try {
                int val = Integer.parseInt(value);
                if (val >= 0 && val <= 6) {
                    return FormValidation.ok();
                }
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("Spot Block Reservation Duration must be an integer between 0 & 6");
        }

        @POST
        public FormValidation doCheckLaunchTimeoutStr(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }
            try {
                int val = Integer.parseInt(value);
                if (val >= 0) {
                    return FormValidation.ok();
                }
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("Launch Timeout must be a non-negative integer (or null)");
        }

        @RequirePOST
        public ListBoxModel doFillZoneItems(
                @QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId,
                @QueryParameter String region,
                @QueryParameter String roleArn,
                @QueryParameter String roleSessionName)
                throws IOException, ServletException {
            checkPermission(EC2Cloud.PROVISION);
            AwsCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(
                    useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
            return EC2AbstractSlave.fillZoneItems(credentialsProvider, region);
        }

        public String getDefaultTenancy() {
            // new templates default to the most secure strategy
            return Tenancy.Default.name();
        }

        /*
         * Validate the Spot Max Bid Price to ensure that it is a floating point number >= .001
         */
        @POST
        public FormValidation doCheckSpotMaxBidPrice(@QueryParameter String spotMaxBidPrice) {
            if (SpotConfiguration.normalizeBid(spotMaxBidPrice) != null) {
                return FormValidation.ok();
            }
            return FormValidation.error("Not a correct bid price");
        }

        public String getDefaultConnectionStrategy() {
            return ConnectionStrategy.PRIVATE_IP.name();
        }

        public List<NodePropertyDescriptor> getNodePropertyDescriptors() {
            return NodePropertyDescriptor.for_(NodeProperty.all(), EC2AbstractSlave.class);
        }

        @RequirePOST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public ListBoxModel doFillTypeItems(@QueryParameter String type) {
            ListBoxModel items = new ListBoxModel();

            List<String> knownValues = InstanceType.knownValues().stream()
                    .map(InstanceType::toString)
                    .sorted()
                    .collect(Collectors.toList());

            for (String value : knownValues) {
                items.add(new ListBoxModel.Option(value, value, Objects.equals(value, type)));
            }

            return items;
        }

        @POST
        public ListBoxModel doFillConnectionStrategyItems(@QueryParameter String connectionStrategy) {
            return Stream.of(ConnectionStrategy.values())
                    .map(v -> {
                        if (v.name().equals(connectionStrategy)) {
                            return new ListBoxModel.Option(v.getDisplayText(), v.name(), true);
                        } else {
                            return new ListBoxModel.Option(v.getDisplayText(), v.name(), false);
                        }
                    })
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        @POST
        public FormValidation doCheckConnectionStrategy(@QueryParameter String connectionStrategy) {
            return Stream.of(ConnectionStrategy.values())
                    .filter(v -> v.name().equals(connectionStrategy))
                    .findFirst()
                    .map(s -> FormValidation.ok())
                    .orElse(FormValidation.error("Could not find selected connection strategy"));
        }

        @POST
        public ListBoxModel doFillAssociateIPStrategyItems(@QueryParameter String associateIPStrategy) {
            checkPermission(EC2Cloud.PROVISION);
            return Stream.of(AssociateIPStrategy.values())
                    .map(v -> {
                        if (v.name().equals(associateIPStrategy)) {
                            return new ListBoxModel.Option(v.getDisplayText(), v.name(), true);
                        } else {
                            return new ListBoxModel.Option(v.getDisplayText(), v.name(), false);
                        }
                    })
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        @POST
        public FormValidation doCheckAssociateIPStrategy(@QueryParameter String associateIPStrategy) {
            checkPermission(EC2Cloud.PROVISION);
            return Stream.of(AssociateIPStrategy.values())
                    .filter(v -> v.name().equals(associateIPStrategy))
                    .findFirst()
                    .map(s -> FormValidation.ok())
                    .orElse(FormValidation.error("Could not find selected associate IP strategy"));
        }

        public String getDefaultAssociateIPStrategy() {
            return AssociateIPStrategy.DEFAULT.name();
        }

        public String getDefaultHostKeyVerificationStrategy() {
            // new templates default to the most secure strategy
            return HostKeyVerificationStrategyEnum.CHECK_NEW_HARD.name();
        }

        @POST
        public ListBoxModel doFillHostKeyVerificationStrategyItems(@QueryParameter String hostKeyVerificationStrategy) {
            return Stream.of(HostKeyVerificationStrategyEnum.values())
                    .map(v -> {
                        if (v.name().equals(hostKeyVerificationStrategy)) {
                            return new ListBoxModel.Option(v.getDisplayText(), v.name(), true);
                        } else {
                            return new ListBoxModel.Option(v.getDisplayText(), v.name(), false);
                        }
                    })
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        @POST
        public FormValidation doCheckHostKeyVerificationStrategy(@QueryParameter String hostKeyVerificationStrategy) {
            Stream<HostKeyVerificationStrategyEnum> stream = Stream.of(HostKeyVerificationStrategyEnum.values());
            Stream<HostKeyVerificationStrategyEnum> filteredStream =
                    stream.filter(v -> v.name().equals(hostKeyVerificationStrategy));
            Optional<HostKeyVerificationStrategyEnum> matched = filteredStream.findFirst();
            Optional<FormValidation> okResult = matched.map(s -> FormValidation.ok());
            return okResult.orElse(FormValidation.error(
                    String.format("Could not find selected host key verification (%s)", hostKeyVerificationStrategy)));
        }

        @POST
        public ListBoxModel doFillTenancyItems(@QueryParameter String tenancy) {
            return Stream.of(Tenancy.values())
                    .map(v -> {
                        if (v.name().equals(tenancy)) {
                            return new ListBoxModel.Option(v.name(), v.name(), true);
                        } else {
                            return new ListBoxModel.Option(v.name(), v.name(), false);
                        }
                    })
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        public String getDefaultEbsEncryptRootVolume() {
            return EbsEncryptRootVolume.DEFAULT.getDisplayText();
        }

        @POST
        public ListBoxModel doFillEbsEncryptRootVolumeItems(@QueryParameter String ebsEncryptRootVolume) {
            return Stream.of(EbsEncryptRootVolume.values())
                    .map(v -> {
                        if (v.name().equals(ebsEncryptRootVolume)) {
                            return new ListBoxModel.Option(v.getDisplayText(), v.name(), true);
                        } else {
                            return new ListBoxModel.Option(v.getDisplayText(), v.name(), false);
                        }
                    })
                    .collect(Collectors.toCollection(ListBoxModel::new));
        }

        @POST
        public FormValidation doEbsEncryptRootVolume(@QueryParameter String ebsEncryptRootVolume) {
            Stream<EbsEncryptRootVolume> stream = Stream.of(EbsEncryptRootVolume.values());
            Stream<EbsEncryptRootVolume> filteredStream =
                    stream.filter(v -> v.name().equals(ebsEncryptRootVolume));
            Optional<EbsEncryptRootVolume> matched = filteredStream.findFirst();
            Optional<FormValidation> okResult = matched.map(s -> FormValidation.ok());
            return okResult.orElse(
                    FormValidation.error(String.format("Could not find selected option (%s)", ebsEncryptRootVolume)));
        }

        @RequirePOST
        public FormValidation doCheckEnclaveEnabled(
                @QueryParameter boolean enclaveEnabled,
                @QueryParameter String type,
                @QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId,
                @QueryParameter String region,
                @QueryParameter String altEC2Endpoint,
                @QueryParameter String roleArn,
                @QueryParameter String roleSessionName) {
            checkPermission(EC2Cloud.PROVISION);
            if (enclaveEnabled && type != null && !type.isEmpty()) {
                AwsCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(
                        useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
                Ec2Client ec2 = AmazonEC2Factory.getInstance()
                        .connect(
                                credentialsProvider,
                                EC2Cloud.parseRegion(region),
                                EC2Cloud.parseEndpoint(altEC2Endpoint));
                DescribeInstanceTypesRequest request = DescribeInstanceTypesRequest.builder()
                        .instanceTypes(InstanceType.fromValue(type))
                        .build();
                DescribeInstanceTypesResponse response = ec2.describeInstanceTypes(request);
                for (InstanceTypeInfo instanceTypeInfo : response.instanceTypes()) {
                    if (!InstanceTypeHypervisor.UNKNOWN_TO_SDK_VERSION.equals(instanceTypeInfo.hypervisor())
                            && !InstanceTypeHypervisor.NITRO.equals(instanceTypeInfo.hypervisor())) {
                        return FormValidation.error("The selected instance type does not use the AWS Nitro System.");
                    }
                    if (NitroEnclavesSupport.UNSUPPORTED.equals(instanceTypeInfo.nitroEnclavesSupport())) {
                        return FormValidation.error("The selected instance type does not support AWS Nitro Enclaves.");
                    }
                }
            }
            return FormValidation.ok();
        }
    }

    /**
     * Helper method to record on-demand provisioning events for monitoring.
     */
    private void recordProvisioningEvent(RunInstancesRequest request, String phase, String errorMessage, int provisionedCount) {
        try {
            String region = getParent().getRegion();
            String availabilityZone = request.placement() != null ? request.placement().availabilityZone() : "unknown";
            String controllerName = getControllerName();
            String cloudName = getParent().getCloudName();
            String jenkinsUrl = Jenkins.get().getRootUrl();
            
            // Use the original string value instead of the enum to preserve R8gd and other new instance types
            String instanceTypeStr = "unknown";
            if (request.instanceType() != null) {
                if (request.instanceType() == InstanceType.UNKNOWN_TO_SDK_VERSION) {
                    // For unknown instance types, use the original string value
                    instanceTypeStr = this.type; // Use the original string from SlaveTemplate
                    LOGGER.log(Level.INFO, "Using fallback instance type string for unknown SDK type: " + instanceTypeStr);
                } else {
                    instanceTypeStr = request.instanceType().toString();
                    LOGGER.log(Level.FINE, "Using SDK recognized instance type: " + instanceTypeStr);
                }
            } else {
                LOGGER.log(Level.WARNING, "Request instance type is null, using 'unknown'");
            }
            LOGGER.log(Level.FINE, "Final instance type for monitoring: " + instanceTypeStr);
            
            ProvisioningEvent event = new ProvisioningEvent(
                region,
                availabilityZone,
                "on-demand-" + System.currentTimeMillis(), // Generate a unique request ID
                instanceTypeStr,
                request.maxCount(),
                request.minCount(),
                provisionedCount,
                controllerName,
                cloudName,
                phase,
                errorMessage,
                jenkinsUrl
            );
            
            EC2ProvisioningMonitor.recordProvisioningEvent(event);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to record provisioning event", e);
        }
    }

    /**
     * Helper method to record spot instance provisioning events for monitoring.
     */
    private void recordSpotProvisioningEvent(RequestSpotInstancesRequest request, String phase, String errorMessage, int provisionedCount) {
        try {
            String region = getParent().getRegion();
            String availabilityZone = request.launchSpecification() != null && 
                                    request.launchSpecification().placement() != null ? 
                                    request.launchSpecification().placement().availabilityZone() : "unknown";
            String controllerName = getControllerName();
            String cloudName = getParent().getCloudName();
            String jenkinsUrl = Jenkins.get().getRootUrl();
            
            // Use the original string value instead of the enum to preserve R8gd and other new instance types
            String instanceTypeStr = "unknown";
            if (request.launchSpecification() != null && request.launchSpecification().instanceType() != null) {
                if (request.launchSpecification().instanceType() == InstanceType.UNKNOWN_TO_SDK_VERSION) {
                    // For unknown instance types, use the original string value
                    instanceTypeStr = this.type; // Use the original string from SlaveTemplate
                    LOGGER.log(Level.INFO, "Using fallback instance type string for unknown SDK type (spot): " + instanceTypeStr);
                } else {
                    instanceTypeStr = request.launchSpecification().instanceType().toString();
                    LOGGER.log(Level.FINE, "Using SDK recognized instance type (spot): " + instanceTypeStr);
                }
            } else {
                LOGGER.log(Level.WARNING, "Spot request launch specification or instance type is null, using 'unknown'");
            }
            LOGGER.log(Level.FINE, "Final instance type for monitoring (spot): " + instanceTypeStr);
            
            ProvisioningEvent event = new ProvisioningEvent(
                region,
                availabilityZone,
                "spot-" + System.currentTimeMillis(), // Generate a unique request ID
                instanceTypeStr,
                request.instanceCount(), // For spot instances, instanceCount is both min and max
                request.instanceCount(),
                provisionedCount,
                controllerName,
                cloudName,
                phase,
                errorMessage,
                jenkinsUrl
            );
            
            EC2ProvisioningMonitor.recordProvisioningEvent(event);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to record spot provisioning event", e);
        }
    }

    /**
     * Get the controller name with multiple fallback strategies.
     */
    private String getControllerName() {
        // Try environment variable first
        String controllerName = System.getenv("JENKINS_BASE_HOSTNAME_SHORT");
        if (controllerName != null && !controllerName.trim().isEmpty()) {
            LOGGER.log(Level.FINE, "Found controller name from JENKINS_BASE_HOSTNAME_SHORT: " + controllerName.trim());
            return controllerName.trim();
        }

        // Try Jenkins EnvVars (might be available when System.getenv is not)
        try {
            java.util.Map<String, String> envVars = hudson.EnvVars.masterEnvVars;
            if (envVars != null) {
                controllerName = envVars.get("JENKINS_BASE_HOSTNAME_SHORT");
                if (controllerName != null && !controllerName.trim().isEmpty()) {
                    return controllerName.trim();
                }
            }
        } catch (Exception e) {
            // Ignore if EnvVars not available
        }

        // Try other environment variables
        controllerName = System.getenv("HOSTNAME");
        if (controllerName != null && !controllerName.trim().isEmpty()) {
            return controllerName.trim();
        }

        controllerName = System.getenv("COMPUTERNAME");
        if (controllerName != null && !controllerName.trim().isEmpty()) {
            return controllerName.trim();
        }

        // Try system properties
        try {
            controllerName = System.getProperty("jenkins.hostname");
            if (controllerName != null && !controllerName.trim().isEmpty()) {
                return controllerName.trim();
            }
        } catch (Exception e) {
            // Ignore security exceptions
        }

        // Try to extract from Jenkins URL
        try {
            String jenkinsUrl = Jenkins.get().getRootUrl();
            if (jenkinsUrl != null) {
                java.net.URL url = new java.net.URL(jenkinsUrl);
                String host = url.getHost();
                if (host != null && !host.trim().isEmpty()) {
                    // Remove domain suffix if present
                    int dotIndex = host.indexOf('.');
                    if (dotIndex > 0) {
                        host = host.substring(0, dotIndex);
                    }
                    return host.trim();
                }
            }
        } catch (Exception e) {
            // Ignore URL parsing exceptions
        }

        // Try Java system hostname
        try {
            controllerName = java.net.InetAddress.getLocalHost().getHostName();
            if (controllerName != null && !controllerName.trim().isEmpty()) {
                // Remove domain suffix if present
                int dotIndex = controllerName.indexOf('.');
                if (dotIndex > 0) {
                    controllerName = controllerName.substring(0, dotIndex);
                }
                return controllerName.trim();
            }
        } catch (Exception e) {
            // Ignore network exceptions
        }

        // Final fallback - also log what env vars we do have for debugging
        LOGGER.log(Level.WARNING, "Could not determine controller name, using fallback: jenkins-controller");
        LOGGER.log(Level.FINE, "Available env vars: HOSTNAME=" + System.getenv("HOSTNAME") + 
                               ", COMPUTERNAME=" + System.getenv("COMPUTERNAME"));
        return "jenkins-controller";
    }
}
