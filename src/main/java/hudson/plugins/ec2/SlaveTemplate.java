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
import static hudson.plugins.ec2.EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED;
import static hudson.plugins.ec2.EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED;
import static hudson.plugins.ec2.EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED;
import static hudson.plugins.ec2.EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT;
import static hudson.plugins.ec2.EC2AbstractSlave.DEFAULT_JAVA_PATH;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;
import com.amazonaws.services.ec2.model.BlockDeviceMapping;
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.CreditSpecificationRequest;
import com.amazonaws.services.ec2.model.DeleteKeyPairRequest;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSubnetsRequest;
import com.amazonaws.services.ec2.model.DescribeSubnetsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.HttpTokensState;
import com.amazonaws.services.ec2.model.IamInstanceProfileSpecification;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceMarketOptionsRequest;
import com.amazonaws.services.ec2.model.InstanceMetadataEndpointState;
import com.amazonaws.services.ec2.model.InstanceMetadataOptionsRequest;
import com.amazonaws.services.ec2.model.InstanceNetworkInterfaceSpecification;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.LaunchSpecification;
import com.amazonaws.services.ec2.model.MarketType;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest;
import com.amazonaws.services.ec2.model.RequestSpotInstancesResult;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.ResourceType;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.ShutdownBehavior;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotMarketOptions;
import com.amazonaws.services.ec2.model.SpotPlacement;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesResult;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
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
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import hudson.plugins.ec2.util.MinimumNumberOfInstancesTimeRangeConfig;
import hudson.security.Permission;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import org.kohsuke.stapler.verb.POST;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
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
import java.util.UUID;

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

    public final InstanceType type;

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

    public final boolean associatePublicIp;

    protected transient EC2Cloud parent;

    public AMITypeData amiType;

    public int launchTimeout;

    public boolean connectBySSHProcess;

    public int maxTotalUses;

    private /* lazily initialized */ DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProperties;

    public int nextSubnet;

    public String currentSubnetId;

    public Tenancy tenancy;

    public EbsEncryptRootVolume ebsEncryptRootVolume;

    private Boolean metadataSupported;

    private Boolean metadataEndpointEnabled;

    private Boolean metadataTokensRequired;

    private Integer metadataHopsLimit;

    private transient/* almost final */ Set<LabelAtom> labelSet;

    private transient/* almost final */Set<String> securityGroupSet;

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
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
                         InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
                         String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String javaPath, String jvmopts,
                         boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, int minimumNumberOfInstances,
                         int minimumNumberOfSpareInstances, String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
                         boolean useEphemeralDevices, String launchTimeoutStr, boolean associatePublicIp,
                         String customDeviceMapping, boolean connectBySSHProcess, boolean monitoring,
                         boolean t2Unlimited, ConnectionStrategy connectionStrategy, int maxTotalUses,
                         List<? extends NodeProperty<?>> nodeProperties, HostKeyVerificationStrategyEnum hostKeyVerificationStrategy, Tenancy tenancy, EbsEncryptRootVolume ebsEncryptRootVolume,
                         Boolean metadataEndpointEnabled, Boolean metadataTokensRequired, Integer metadataHopsLimit, Boolean metadataSupported) {

        if(StringUtils.isNotBlank(remoteAdmin) || StringUtils.isNotBlank(jvmopts) || StringUtils.isNotBlank(tmpDir)){
            LOGGER.log(Level.FINE, "As remoteAdmin, jvmopts or tmpDir is not blank, we must ensure the user has ADMINISTER rights.");
            // Can be null during tests
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j != null)
                j.checkPermission(Jenkins.ADMINISTER);
        }

        this.ami = ami;
        this.zone = zone;
        this.spotConfig = spotConfig;
        this.securityGroups = securityGroups;
        this.remoteFS = remoteFS;
        this.amiType = amiType;
        this.type = type;
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
            this.javaPath = DEFAULT_JAVA_PATH;
        }

        this.jvmopts = jvmopts;
        this.stopOnTerminate = stopOnTerminate;
        this.subnetId = subnetId;
        this.tags = tags;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.associatePublicIp = associatePublicIp;
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

        this.hostKeyVerificationStrategy = hostKeyVerificationStrategy != null ? hostKeyVerificationStrategy : HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT;
        this.tenancy = tenancy != null ? tenancy : Tenancy.Default;
        this.ebsEncryptRootVolume = ebsEncryptRootVolume != null ? ebsEncryptRootVolume : EbsEncryptRootVolume.DEFAULT;
        this.metadataSupported = metadataSupported != null ? metadataSupported : DEFAULT_METADATA_SUPPORTED;
        this.metadataEndpointEnabled = metadataEndpointEnabled != null ? metadataEndpointEnabled : DEFAULT_METADATA_ENDPOINT_ENABLED;
        this.metadataTokensRequired = metadataTokensRequired != null ? metadataTokensRequired : DEFAULT_METADATA_TOKENS_REQUIRED;
        this.metadataHopsLimit = metadataHopsLimit != null ? metadataHopsLimit : DEFAULT_METADATA_HOPS_LIMIT;
        readResolve(); // initialize
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
                         InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
                         String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String javaPath, String jvmopts,
                         boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, int minimumNumberOfInstances,
                         int minimumNumberOfSpareInstances, String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
                         boolean useEphemeralDevices, String launchTimeoutStr, boolean associatePublicIp,
                         String customDeviceMapping, boolean connectBySSHProcess, boolean monitoring,
                         boolean t2Unlimited, ConnectionStrategy connectionStrategy, int maxTotalUses,
                         List<? extends NodeProperty<?>> nodeProperties, HostKeyVerificationStrategyEnum hostKeyVerificationStrategy, Tenancy tenancy, EbsEncryptRootVolume ebsEncryptRootVolume,
                         Boolean metadataSupported, Boolean metadataEndpointEnabled, Boolean metadataTokensRequired, Integer metadataHopsLimit) {
        this(ami, zone, spotConfig, securityGroups, remoteFS,
               type, ebsOptimized, labelString, mode, description, initScript,
               tmpDir, userData, numExecutors, remoteAdmin, amiType, DEFAULT_JAVA_PATH, jvmopts,
               stopOnTerminate, subnetId, tags, idleTerminationMinutes, minimumNumberOfInstances,
               minimumNumberOfSpareInstances, instanceCapStr, iamInstanceProfile, deleteRootOnTermination,
               useEphemeralDevices, launchTimeoutStr, associatePublicIp,
               customDeviceMapping, connectBySSHProcess, monitoring,
               t2Unlimited, connectionStrategy, maxTotalUses,
               nodeProperties, hostKeyVerificationStrategy, tenancy, null, metadataEndpointEnabled,
               metadataTokensRequired, metadataHopsLimit, DEFAULT_METADATA_SUPPORTED);
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
                         InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
                         String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
                         boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, int minimumNumberOfInstances,
                         int minimumNumberOfSpareInstances, String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
                         boolean useEphemeralDevices, String launchTimeoutStr, boolean associatePublicIp,
                         String customDeviceMapping, boolean connectBySSHProcess, boolean monitoring,
                         boolean t2Unlimited, ConnectionStrategy connectionStrategy, int maxTotalUses,
                         List<? extends NodeProperty<?>> nodeProperties, HostKeyVerificationStrategyEnum hostKeyVerificationStrategy, Tenancy tenancy, EbsEncryptRootVolume ebsEncryptRootVolume) {
        this(ami, zone, spotConfig, securityGroups, remoteFS,
                type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, DEFAULT_JAVA_PATH, jvmopts,
                stopOnTerminate, subnetId, tags, idleTerminationMinutes, minimumNumberOfInstances,
                minimumNumberOfSpareInstances, instanceCapStr, iamInstanceProfile, deleteRootOnTermination,
                useEphemeralDevices, launchTimeoutStr, associatePublicIp,
                customDeviceMapping, connectBySSHProcess, monitoring,
                t2Unlimited, connectionStrategy, maxTotalUses,
                nodeProperties, hostKeyVerificationStrategy, tenancy, null, DEFAULT_METADATA_ENDPOINT_ENABLED,
                DEFAULT_METADATA_TOKENS_REQUIRED, DEFAULT_METADATA_HOPS_LIMIT, DEFAULT_METADATA_SUPPORTED);
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
                         InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
                         String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
                         boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, int minimumNumberOfInstances,
                         int minimumNumberOfSpareInstances, String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
                         boolean useEphemeralDevices, String launchTimeoutStr, boolean associatePublicIp,
                         String customDeviceMapping, boolean connectBySSHProcess, boolean monitoring,
                         boolean t2Unlimited, ConnectionStrategy connectionStrategy, int maxTotalUses,
                         List<? extends NodeProperty<?>> nodeProperties, HostKeyVerificationStrategyEnum hostKeyVerificationStrategy, Tenancy tenancy) {
        this(ami, zone, spotConfig, securityGroups, remoteFS,
                type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts,
                stopOnTerminate, subnetId, tags, idleTerminationMinutes, minimumNumberOfInstances,
                minimumNumberOfSpareInstances, instanceCapStr, iamInstanceProfile, deleteRootOnTermination,
                useEphemeralDevices, launchTimeoutStr, associatePublicIp,
                customDeviceMapping, connectBySSHProcess, monitoring,
                t2Unlimited, connectionStrategy, maxTotalUses,
                nodeProperties, hostKeyVerificationStrategy, tenancy, null);
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
                         InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
                         String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
                         boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, int minimumNumberOfInstances,
                         int minimumNumberOfSpareInstances, String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
                         boolean useEphemeralDevices, boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp,
                         String customDeviceMapping, boolean connectBySSHProcess, boolean monitoring,
                         boolean t2Unlimited, ConnectionStrategy connectionStrategy, int maxTotalUses,
                         List<? extends NodeProperty<?>> nodeProperties, HostKeyVerificationStrategyEnum hostKeyVerificationStrategy) {
        this(ami, zone, spotConfig, securityGroups, remoteFS,
                type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts,
                stopOnTerminate, subnetId, tags, idleTerminationMinutes, minimumNumberOfInstances,
                minimumNumberOfSpareInstances, instanceCapStr, iamInstanceProfile, deleteRootOnTermination,
                useEphemeralDevices, launchTimeoutStr, associatePublicIp,
                customDeviceMapping, connectBySSHProcess, monitoring,
                t2Unlimited, connectionStrategy, maxTotalUses,
                nodeProperties, hostKeyVerificationStrategy, Tenancy.backwardsCompatible(useDedicatedTenancy));
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
            InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
            String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
            boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, int minimumNumberOfInstances,
            int minimumNumberOfSpareInstances, String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
            boolean useEphemeralDevices, boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp,
            String customDeviceMapping, boolean connectBySSHProcess, boolean monitoring,
            boolean t2Unlimited, ConnectionStrategy connectionStrategy, int maxTotalUses,
            List<? extends NodeProperty<?>> nodeProperties) {
        this(ami, zone, spotConfig, securityGroups, remoteFS,
                type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts,
                stopOnTerminate, subnetId, tags, idleTerminationMinutes, minimumNumberOfInstances,
                minimumNumberOfSpareInstances, instanceCapStr, iamInstanceProfile, deleteRootOnTermination,
                useEphemeralDevices, useDedicatedTenancy, launchTimeoutStr, associatePublicIp,
                customDeviceMapping, connectBySSHProcess, monitoring,
                t2Unlimited, connectionStrategy, maxTotalUses,
                nodeProperties, null);
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
            InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
            String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
            boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, int minimumNumberOfInstances,
            String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
            boolean useEphemeralDevices, boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp,
            String customDeviceMapping, boolean connectBySSHProcess, boolean monitoring,
            boolean t2Unlimited, ConnectionStrategy connectionStrategy, int maxTotalUses,List<? extends NodeProperty<?>> nodeProperties ) {
        this(ami, zone, spotConfig, securityGroups, remoteFS, type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts, stopOnTerminate, subnetId, tags,
                idleTerminationMinutes, minimumNumberOfInstances, 0, instanceCapStr, iamInstanceProfile, deleteRootOnTermination,
                useEphemeralDevices, useDedicatedTenancy, launchTimeoutStr, associatePublicIp, customDeviceMapping,
                connectBySSHProcess, monitoring, t2Unlimited, connectionStrategy, maxTotalUses, nodeProperties);
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
            InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
            String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
            boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, int minimumNumberOfInstances,
            String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
            boolean useEphemeralDevices, boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp,
            String customDeviceMapping, boolean connectBySSHProcess, boolean monitoring,
            boolean t2Unlimited, ConnectionStrategy connectionStrategy, int maxTotalUses) {
        this(ami, zone, spotConfig, securityGroups, remoteFS, type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts, stopOnTerminate, subnetId, tags,
                idleTerminationMinutes, minimumNumberOfInstances, instanceCapStr, iamInstanceProfile, deleteRootOnTermination,
                useEphemeralDevices, useDedicatedTenancy, launchTimeoutStr, associatePublicIp, customDeviceMapping,
                connectBySSHProcess, monitoring, t2Unlimited, connectionStrategy, maxTotalUses, Collections.emptyList());
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
                         InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
                         String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
                         boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes,
                         String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
                         boolean useEphemeralDevices, boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp,
                         String customDeviceMapping, boolean connectBySSHProcess, boolean monitoring,
                         boolean t2Unlimited, ConnectionStrategy connectionStrategy, int maxTotalUses) {
        this(ami, zone, spotConfig, securityGroups, remoteFS, type, ebsOptimized, labelString, mode, description, initScript,
          tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts, stopOnTerminate, subnetId, tags,
          idleTerminationMinutes, 0, instanceCapStr, iamInstanceProfile, deleteRootOnTermination, useEphemeralDevices,
          useDedicatedTenancy, launchTimeoutStr, associatePublicIp, customDeviceMapping, connectBySSHProcess,
          monitoring, t2Unlimited, connectionStrategy, maxTotalUses);
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
            InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
            String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
            boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes,
            boolean usePrivateDnsName, String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
            boolean useEphemeralDevices, boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp,
            String customDeviceMapping, boolean connectBySSHProcess, boolean connectUsingPublicIp, boolean monitoring,
            boolean t2Unlimited) {
        this(ami, zone, spotConfig, securityGroups, remoteFS, type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts, stopOnTerminate, subnetId, tags,
                idleTerminationMinutes, instanceCapStr, iamInstanceProfile, deleteRootOnTermination, useEphemeralDevices,
                useDedicatedTenancy, launchTimeoutStr, associatePublicIp, customDeviceMapping, connectBySSHProcess,
                monitoring, t2Unlimited, ConnectionStrategy.backwardsCompatible(usePrivateDnsName, connectUsingPublicIp, associatePublicIp), -1);
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
            InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
            String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
            boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes,
            boolean usePrivateDnsName, String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
            boolean useEphemeralDevices, boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp,
            String customDeviceMapping, boolean connectBySSHProcess, boolean connectUsingPublicIp) {
        this(ami, zone, spotConfig, securityGroups, remoteFS, type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts, stopOnTerminate, subnetId, tags,
                idleTerminationMinutes, usePrivateDnsName, instanceCapStr, iamInstanceProfile, deleteRootOnTermination, useEphemeralDevices,
                useDedicatedTenancy, launchTimeoutStr, associatePublicIp, customDeviceMapping, connectBySSHProcess,
                connectUsingPublicIp, false, false);
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
            InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
            String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
            boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes,
            boolean usePrivateDnsName, String instanceCapStr, String iamInstanceProfile, boolean useEphemeralDevices,
            boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp, String customDeviceMapping,
            boolean connectBySSHProcess) {
        this(ami, zone, spotConfig, securityGroups, remoteFS, type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts, stopOnTerminate, subnetId, tags,
                idleTerminationMinutes, usePrivateDnsName, instanceCapStr, iamInstanceProfile, false, useEphemeralDevices,
                useDedicatedTenancy, launchTimeoutStr, associatePublicIp, customDeviceMapping, connectBySSHProcess, false);
    }

    @Deprecated
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
            InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
            String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
            boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes,
            boolean usePrivateDnsName, String instanceCapStr, String iamInstanceProfile, boolean useEphemeralDevices,
            boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp, String customDeviceMapping) {
        this(ami, zone, spotConfig, securityGroups, remoteFS, type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, amiType, jvmopts, stopOnTerminate, subnetId, tags,
                idleTerminationMinutes, usePrivateDnsName, instanceCapStr, iamInstanceProfile, useEphemeralDevices,
                useDedicatedTenancy, launchTimeoutStr, associatePublicIp, customDeviceMapping, false);
    }

    /**
     * Backward compatible constructor for reloading previous version data
     */
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
            String sshPort, InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description,
            String initScript, String tmpDir, String userData, String numExecutors, String remoteAdmin, String rootCommandPrefix,
            String slaveCommandPrefix, String slaveCommandSuffix, String jvmopts, boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes,
            boolean usePrivateDnsName, String instanceCapStr, String iamInstanceProfile, boolean useEphemeralDevices,
            String launchTimeoutStr) {
        this(ami, zone, spotConfig, securityGroups, remoteFS, type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, new UnixData(rootCommandPrefix, slaveCommandPrefix, slaveCommandSuffix, sshPort, null),
                jvmopts, stopOnTerminate, subnetId, tags, idleTerminationMinutes, usePrivateDnsName, instanceCapStr, iamInstanceProfile,
                useEphemeralDevices, false, launchTimeoutStr, false, null);
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
        if (securityGroups == null || "".equals(securityGroups.trim())) {
            return Collections.emptySet();
        } else {
            return new HashSet<>(Arrays.asList(securityGroups.split("\\s*,\\s*")));
        }
    }

    public int getNumExecutors() {
        try {
            return Integer.parseInt(numExecutors);
        } catch (NumberFormatException e) {
            return EC2AbstractSlave.toNumExecutors(type);
        }
    }

    public int getSshPort() {
        try {
            String sshPort = "";
            if (amiType.isUnix()) {
                sshPort = ((UnixData) amiType).getSshPort();
            }
            if (amiType.isMac()) {
                sshPort = ((MacData) amiType).getSshPort();
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
        return (amiType.isUnix() ? ((UnixData) amiType).getRootCommandPrefix() : (amiType.isMac() ? ((MacData) amiType).getRootCommandPrefix():""));
    }

    public String getSlaveCommandPrefix() {
        return (amiType.isUnix() ? ((UnixData) amiType).getSlaveCommandPrefix() : (amiType.isMac() ? ((MacData) amiType).getSlaveCommandPrefix() : ""));
    }

    public String getSlaveCommandSuffix() {
        return (amiType.isUnix() ? ((UnixData) amiType).getSlaveCommandSuffix() : (amiType.isMac() ? ((MacData) amiType).getSlaveCommandSuffix() : ""));
    }

    public String chooseSubnetId() {
        if (StringUtils.isBlank(subnetId)) {
            return null;
        } else {
            String[] subnetIdList= getSubnetId().split(EC2_RESOURCE_ID_DELIMETERS);

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

    public boolean getAssociatePublicIp() {
        return associatePublicIp;
    }

    @Deprecated
    @DataBoundSetter
    public void setConnectUsingPublicIp(boolean connectUsingPublicIp) {
        this.connectUsingPublicIp = connectUsingPublicIp;
        this.connectionStrategy = ConnectionStrategy.backwardsCompatible(this.usePrivateDnsName, this.connectUsingPublicIp, this.associatePublicIp);
    }

    @Deprecated
    @DataBoundSetter
    public void setUsePrivateDnsName(boolean usePrivateDnsName) {
        this.usePrivateDnsName = usePrivateDnsName;
        this.connectionStrategy = ConnectionStrategy.backwardsCompatible(this.usePrivateDnsName, this.connectUsingPublicIp, this.associatePublicIp);
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
        if (null == tags)
            return null;
        return Collections.unmodifiableList(tags);
    }

    public String getidleTerminationMinutes() {
        return idleTerminationMinutes;
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
    public void setMinimumNumberOfInstancesTimeRangeConfig(MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig) {
        this.minimumNumberOfInstancesTimeRangeConfig = minimumNumberOfInstancesTimeRangeConfig;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public int getSpotBlockReservationDuration() {
        if (spotConfig == null)
            return 0;
        return spotConfig.getSpotBlockReservationDuration();
    }

    public String getSpotBlockReservationDurationStr() {
        if (spotConfig == null) {
            return "";
        } else {
            int dur = getSpotBlockReservationDuration();
            if (dur == 0)
                return "";
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
        if (spotConfig == null)
            return null;
        return SpotConfiguration.normalizeBid(spotConfig.getSpotMaxBidPrice());
    }

    public String getIamInstanceProfile() {
        return iamInstanceProfile;
    }

    @DataBoundSetter
    public void setHostKeyVerificationStrategy(HostKeyVerificationStrategyEnum hostKeyVerificationStrategy) {
        this.hostKeyVerificationStrategy = (hostKeyVerificationStrategy != null) ? hostKeyVerificationStrategy : HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT;
    }

    @NonNull
    public HostKeyVerificationStrategyEnum getHostKeyVerificationStrategy() {
        return hostKeyVerificationStrategy != null ? hostKeyVerificationStrategy : HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT;
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

    @Override
    public String toString() {
        return "SlaveTemplate{" +
                "description='" + description + '\'' +
                ", labels='" + labels + '\'' +
                '}';
    }

    public int getMaxTotalUses() {
        return maxTotalUses;
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

    public DescribableList<NodeProperty<?>, NodePropertyDescriptor> getNodeProperties() {
        return Objects.requireNonNull(nodeProperties);
    }

    public enum ProvisionOptions { ALLOW_CREATE, FORCE_CREATE }

    /**
     * Provisions a new EC2 agent or starts a previously stopped on-demand instance.
     *
     * @return always non-null. This needs to be then added to {@link Hudson#addNode(Node)}.
     */
    @NonNull
    public List<EC2AbstractSlave> provision(int number, EnumSet<ProvisionOptions> provisionOptions) throws AmazonClientException, IOException {
        final Image image = getImage();
        if (this.spotConfig != null) {
            if (provisionOptions.contains(ProvisionOptions.ALLOW_CREATE) || provisionOptions.contains(ProvisionOptions.FORCE_CREATE)) {
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
            if ( (node.getInstanceId().equals(instance.getInstanceId())) &&
                    (! (instance.getState().getName().equalsIgnoreCase(InstanceStateName.Stopped.toString())
                ))
               ){
                logInstanceCheck(instance, ". false - found existing corresponding Jenkins agent: " + node.getInstanceId());
                return false;
            }
        }
        logInstanceCheck(instance, " true - Instance is not connected to Jenkins");
        return true;
    }

    private void logInstanceCheck(Instance instance, String message) {
        logProvisionInfo("checkInstance: " + instance.getInstanceId() + "." + message);
    }

    private boolean isSameIamInstanceProfile(Instance instance) {
        return StringUtils.isBlank(getIamInstanceProfile()) ||
                (instance.getIamInstanceProfile() != null &&
                        instance.getIamInstanceProfile().getArn().equals(getIamInstanceProfile()));

    }

    private boolean isTerminatingOrShuttindDown(String instanceStateName) {
        return instanceStateName.equalsIgnoreCase(InstanceStateName.Terminated.toString())
                || instanceStateName.equalsIgnoreCase(InstanceStateName.ShuttingDown.toString());
    }

    private void logProvisionInfo(String message) {
        LOGGER.info(this + ". " + message);
    }

    HashMap<RunInstancesRequest, List<Filter>> makeRunInstancesRequestAndFilters(Image image, int number, AmazonEC2 ec2) throws IOException {
        return makeRunInstancesRequestAndFilters(image, number, ec2, true);
    }

    @Deprecated
    HashMap<RunInstancesRequest, List<Filter>> makeRunInstancesRequestAndFilters(int number, AmazonEC2 ec2) throws IOException {
        return makeRunInstancesRequestAndFilters(getImage(), number, ec2);
    }

    HashMap<RunInstancesRequest, List<Filter>> makeRunInstancesRequestAndFilters(Image image, int number, AmazonEC2 ec2, boolean rotateSubnet) throws IOException {
        String imageId = image.getImageId();
        RunInstancesRequest riRequest = new RunInstancesRequest(imageId, 1, number).withInstanceType(type);
        riRequest.setEbsOptimized(ebsOptimized);
        riRequest.setMonitoring(monitoring);

        if (t2Unlimited){
            CreditSpecificationRequest creditRequest = new CreditSpecificationRequest();
            creditRequest.setCpuCredits("unlimited");
            riRequest.setCreditSpecification(creditRequest);
        }

        setupBlockDeviceMappings(image, riRequest.getBlockDeviceMappings());

        if(stopOnTerminate){
            riRequest.setInstanceInitiatedShutdownBehavior(ShutdownBehavior.Stop);
            logProvisionInfo("Setting Instance Initiated Shutdown Behavior : ShutdownBehavior.Stop");
        }else{
            riRequest.setInstanceInitiatedShutdownBehavior(ShutdownBehavior.Terminate);
            logProvisionInfo("Setting Instance Initiated Shutdown Behavior : ShutdownBehavior.Terminate");
        }

        List<Filter> diFilters = new ArrayList<>();
        diFilters.add(new Filter("image-id").withValues(imageId));
        diFilters.add(new Filter("instance-type").withValues(type.toString()));

        KeyPair keyPair = getKeyPair(ec2);
        if (keyPair != null) {
            logProvisionInfo("Static SSH key was configured; adding it to the request filter");
            riRequest.setKeyName(keyPair.getKeyName());
            diFilters.add(new Filter("key-name").withValues(keyPair.getKeyName()));
        } else {
            logProvisionInfo("Static SSH key was not configured; keys will be managed dynamically");
        }
        riRequest.setUserData(Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8)));


        if (StringUtils.isNotBlank(getZone())) {
            Placement placement = new Placement(getZone());
            if (getTenancyAttribute().equals(Tenancy.Dedicated)) {
                placement.setTenancy("dedicated");
            }
            riRequest.setPlacement(placement);
            diFilters.add(new Filter("availability-zone").withValues(getZone()));
        }

        if(getTenancyAttribute().equals(Tenancy.Host)){
            Placement placement = new Placement();
            placement.setTenancy("host");
            riRequest.setPlacement(placement);
            diFilters.add(new Filter("tenancy").withValues(placement.getTenancy()));
        }else if(getTenancyAttribute().equals(Tenancy.Default)){
            Placement placement = new Placement();
            placement.setTenancy("default");
            riRequest.setPlacement(placement);
            diFilters.add(new Filter("tenancy").withValues(placement.getTenancy()));
        }

        String subnetId = chooseSubnetId(rotateSubnet);

        InstanceNetworkInterfaceSpecification net = new InstanceNetworkInterfaceSpecification();
        if (StringUtils.isNotBlank(subnetId)) {
            if (getAssociatePublicIp()) {
                net.setSubnetId(subnetId);
            } else {
                riRequest.setSubnetId(subnetId);
            }

            diFilters.add(new Filter("subnet-id").withValues(subnetId));

            /*
             * If we have a subnet ID then we can only use VPC security groups
             */
            if (!getSecurityGroupSet().isEmpty()) {
                List<String> groupIds = getEc2SecurityGroups(ec2);

                if (!groupIds.isEmpty()) {
                    if (getAssociatePublicIp()) {
                        net.setGroups(groupIds);
                    } else {
                        riRequest.setSecurityGroupIds(groupIds);
                    }

                    diFilters.add(new Filter("instance.group-id").withValues(groupIds));
                }
            }
        } else {
            List<String> groupIds = getSecurityGroupsBy("group-name", securityGroupSet, ec2)
                                            .getSecurityGroups()
                                            .stream().map(SecurityGroup::getGroupId)
                                            .collect(Collectors.toList());
            if (getAssociatePublicIp()) {
                net.setGroups(groupIds);
            } else {
                riRequest.setSecurityGroups(securityGroupSet);
            }
            if (!groupIds.isEmpty()) {
                diFilters.add(new Filter("instance.group-id").withValues(groupIds));
            }
        }

        net.setAssociatePublicIpAddress(getAssociatePublicIp());
        net.setDeviceIndex(0);

        if (getAssociatePublicIp()) {
            riRequest.withNetworkInterfaces(net);
        }

        HashSet<Tag> instTags = buildTags(EC2Cloud.EC2_SLAVE_TYPE_DEMAND);
        for (Tag tag : instTags) {
            diFilters.add(new Filter("tag:" + tag.getKey()).withValues(tag.getValue()));
        }

        if (StringUtils.isNotBlank(getIamInstanceProfile())) {
            riRequest.setIamInstanceProfile(new IamInstanceProfileSpecification().withArn(getIamInstanceProfile()));
        }

        List<TagSpecification> tagList = new ArrayList<>();
        TagSpecification tagSpecification = new TagSpecification();
        tagSpecification.setTags(instTags);
        tagList.add(tagSpecification.clone().withResourceType(ResourceType.Instance));
        tagList.add(tagSpecification.clone().withResourceType(ResourceType.Volume));
        riRequest.setTagSpecifications(tagList);

        if (metadataSupported) {
            InstanceMetadataOptionsRequest instanceMetadataOptionsRequest = new InstanceMetadataOptionsRequest();
            instanceMetadataOptionsRequest.setHttpEndpoint(metadataEndpointEnabled ? InstanceMetadataEndpointState.Enabled.toString() : InstanceMetadataEndpointState.Disabled.toString());
            instanceMetadataOptionsRequest.setHttpPutResponseHopLimit(metadataHopsLimit == null ? EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT : metadataHopsLimit);
            instanceMetadataOptionsRequest.setHttpTokens(
                metadataTokensRequired ? HttpTokensState.Required.toString() : HttpTokensState.Optional.toString());
            riRequest.setMetadataOptions(instanceMetadataOptionsRequest);
        }

        HashMap<RunInstancesRequest, List<Filter>> ret = new HashMap<>();
        ret.put(riRequest, diFilters);
        return ret;
    }

    @Deprecated
    HashMap<RunInstancesRequest, List<Filter>> makeRunInstancesRequestAndFilters(int number, AmazonEC2 ec2, boolean rotateSubnet) throws IOException {
        return makeRunInstancesRequestAndFilters(getImage(), number, ec2, rotateSubnet);
    }

    /**
     * Provisions an On-demand EC2 agent by launching a new instance or starting a previously-stopped instance.
     */
    private List<EC2AbstractSlave> provisionOndemand(Image image, int number, EnumSet<ProvisionOptions> provisionOptions)
            throws IOException {
        return provisionOndemand(image, number, provisionOptions, false, false);
    }

    /**
     * Provisions an On-demand EC2 agent by launching a new instance or starting a previously-stopped instance.
     * When using dynamic ssh key management no attempt will be made to re-attach any stopped or orphaned nodes
     */
    private List<EC2AbstractSlave> provisionOndemand(Image image, int number, EnumSet<ProvisionOptions> provisionOptions, boolean spotWithoutBidPrice, boolean fallbackSpotToOndemand)
            throws IOException {
        AmazonEC2 ec2 = getParent().connect();
        DescribeInstancesRequest diRequest;
        RunInstancesRequest riRequest = null;
        List<InstanceInfo> newInstances;
        List<Instance> orphansOrStopped;

        HashMap<RunInstancesRequest, List<Filter>> runInstancesRequestFilterMap = makeRunInstancesRequestAndFilters(image, number, ec2);
        Map.Entry<RunInstancesRequest, List<Filter>> entry = runInstancesRequestFilterMap.entrySet().iterator().next();
        riRequest = entry.getKey();
        if (isUsingDynamicSshKeys()) {
            logProvisionInfo("Using dynamic ssh key management, will not attempt to re-use any existing instance");
            orphansOrStopped = Collections.emptyList();

        } else {
            logProvisionInfo("Considering launching");

            List<Filter> diFilters = entry.getValue();

            diRequest = new DescribeInstancesRequest().withFilters(diFilters);

            logProvisionInfo("Looking for existing instances with describe-instance: " + diRequest);

            DescribeInstancesResult diResult = ec2.describeInstances(diRequest);
            orphansOrStopped = findOrphansOrStopped(diResult, number);

            if (orphansOrStopped.isEmpty() && !provisionOptions.contains(ProvisionOptions.FORCE_CREATE) &&
                    !provisionOptions.contains(ProvisionOptions.ALLOW_CREATE)) {
                logProvisionInfo("No existing instance found - but cannot create new instance");
                return null;
            }

            wakeOrphansOrStoppedUp(ec2, orphansOrStopped);

            if (orphansOrStopped.size() == number) {
                return toSlaves(InstanceInfo.fromInstances(orphansOrStopped, getParent()));
            }

            riRequest.setMaxCount(number - orphansOrStopped.size());
        }


        if (spotWithoutBidPrice) {
            InstanceMarketOptionsRequest instanceMarketOptionsRequest = new InstanceMarketOptionsRequest().withMarketType(MarketType.Spot);
            if (getSpotBlockReservationDuration() != 0) {
                SpotMarketOptions spotOptions = new SpotMarketOptions().withBlockDurationMinutes(getSpotBlockReservationDuration() * 60);
                instanceMarketOptionsRequest.setSpotOptions(spotOptions);
            }
            riRequest.setInstanceMarketOptions(instanceMarketOptionsRequest);

            try {
                newInstances = createNewOnDemandInstances(ec2, riRequest);
            } catch (AmazonEC2Exception e) {
                if (fallbackSpotToOndemand && e.getErrorCode().equals("InsufficientInstanceCapacity")) {
                    logProvisionInfo("There is no spot capacity available matching your request, falling back to on-demand instance.");
                    riRequest.setInstanceMarketOptions(new InstanceMarketOptionsRequest());
                    newInstances = createNewOnDemandInstances(ec2, riRequest);
                } else {
                    throw e;
                }
            }
        } else {
            newInstances = createNewOnDemandInstances(ec2, riRequest);
        }
        // Have to create a new instance

        if (newInstances.isEmpty()) {
            logProvisionInfo("No new instances were created");
        }

        newInstances.addAll(InstanceInfo.fromInstances(orphansOrStopped, getParent()));

        return toSlaves(newInstances);
    }

    List<InstanceInfo> createNewOnDemandInstances(AmazonEC2 ec2, RunInstancesRequest riRequest) throws IOException {
        if (isUsingDynamicSshKeys()) {
            // no static ssh key defined, so submit riRequest.maxCount requests
            // each with a unique keypair (instead of a single request for multiple identical instances)
            LOGGER.fine(() -> "no static ssh credential configured, will use dynamic ssh keys instead");
            List<InstanceInfo> instances = new ArrayList<>();
            int maxRequested = riRequest.getMaxCount();
            riRequest.setMaxCount(1);
            for (int i=0;i < maxRequested; i++) {
                KeyPair keyPair = EC2Cloud.createKeyPair(ec2);
                riRequest.setKeyName(keyPair.getKeyName());
                Instance instance = ec2.runInstances(riRequest).getReservation().getInstances().get(0);
                instances.add(new InstanceInfo(instance, keyPair));
            }
            return instances;
        } else {
            // using a static ssh key
            return InstanceInfo.fromInstances(ec2.runInstances(riRequest).getReservation().getInstances(), getParent());
        }
    }

    void wakeOrphansOrStoppedUp(AmazonEC2 ec2, List<Instance> orphansOrStopped) {
        List<String> instances = new ArrayList<>();
        for(Instance instance : orphansOrStopped) {
            if (instance.getState().getName().equalsIgnoreCase(InstanceStateName.Stopping.toString())
                    || instance.getState().getName().equalsIgnoreCase(InstanceStateName.Stopped.toString())) {
                logProvisionInfo("Found stopped instances - will start it: " + instance);
                instances.add(instance.getInstanceId());
            } else {
                // Should be pending or running at this point, just let it come up
                logProvisionInfo("Found existing pending or running: " + instance.getState().getName() + " instance: " + instance);
            }
        }

        if (!instances.isEmpty()) {
            StartInstancesRequest siRequest = new StartInstancesRequest(instances);
            StartInstancesResult siResult = ec2.startInstances(siRequest);
            logProvisionInfo("Result of starting stopped instances:" + siResult);
        }

    }

    List<EC2AbstractSlave> toSlaves(List<InstanceInfo> newInstances) throws IOException {
        LOGGER.fine(() -> "converting instances to agents.....");
        try {
            List<EC2AbstractSlave> slaves = new ArrayList<>(newInstances.size());
            for (InstanceInfo info : newInstances) {
                slaves.add(newOndemandSlave(info));
                logProvisionInfo("Return instance: " + info.getInstance());
            }
            return slaves;
        } catch (FormException e) {
            throw new AssertionError(e); // we should have discovered all
            // configuration issues upfront
        }
    }

    List<Instance> findOrphansOrStopped(DescribeInstancesResult diResult, int number) {
        List<Instance> orphansOrStopped = new ArrayList<>();
        int count = 0;
        for (Reservation reservation : diResult.getReservations()) {
            for (Instance instance : reservation.getInstances()) {
                if (!isSameIamInstanceProfile(instance)) {
                    logInstanceCheck(instance, ". false - IAM Instance profile does not match: " + instance.getIamInstanceProfile());
                    continue;
                }

                if (isTerminatingOrShuttindDown(instance.getState().getName())) {
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
        if (!"ebs".equals(image.getRootDeviceType())) {
            return;
        }

        // get the root device (only one expected in the blockmappings)
        final List<BlockDeviceMapping> rootDeviceMappings = image.getBlockDeviceMappings();
        if (rootDeviceMappings.size() == 0) {
            LOGGER.warning("AMI missing block devices");
            return;
        }
        BlockDeviceMapping rootMapping = rootDeviceMappings.get(0);
        LOGGER.info("AMI had " + rootMapping.getDeviceName());
        LOGGER.info(rootMapping.getEbs().toString());

        // Create a shadow of the AMI mapping (doesn't like reusing rootMapping directly)
        BlockDeviceMapping newMapping = rootMapping.clone();

        if (deleteRootOnTermination) {
            // Check if the root device is already in the mapping and update it
            for (final BlockDeviceMapping mapping : deviceMappings) {
                LOGGER.info("Request had " + mapping.getDeviceName());
                if (rootMapping.getDeviceName().equals(mapping.getDeviceName())) {
                    mapping.getEbs().setDeleteOnTermination(Boolean.TRUE);
                    return;
                }
            }

            // pass deleteRootOnTermination to shadow of the AMI mapping
            newMapping.getEbs().setDeleteOnTermination(Boolean.TRUE);
        }

        newMapping.getEbs().setEncrypted(ebsEncryptRootVolume.getValue());
        String message = String.format("EBS default encryption value set to: %s (%s)", ebsEncryptRootVolume.getDisplayText(), ebsEncryptRootVolume.getValue());
        logProvisionInfo(message);
        deviceMappings.add(0, newMapping);

    }

    private List<BlockDeviceMapping> getNewEphemeralDeviceMapping(Image image) {

        final List<BlockDeviceMapping> oldDeviceMapping = image.getBlockDeviceMappings();

        final Set<String> occupiedDevices = new HashSet<>();
        for (final BlockDeviceMapping mapping : oldDeviceMapping) {

            occupiedDevices.add(mapping.getDeviceName());
        }

        final List<String> available = new ArrayList<>(
                Arrays.asList("ephemeral0", "ephemeral1", "ephemeral2", "ephemeral3"));

        final List<BlockDeviceMapping> newDeviceMapping = new ArrayList<>(4);
        for (char suffix = 'b'; suffix <= 'z' && !available.isEmpty(); suffix++) {

            final String deviceName = String.format("/dev/xvd%s", suffix);

            if (occupiedDevices.contains(deviceName))
                continue;

            final BlockDeviceMapping newMapping = new BlockDeviceMapping().withDeviceName(deviceName).withVirtualName(
                    available.get(0));

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
        return Stream.of(Util.tokenize(Util.fixNull(attr)))
            .collect(Collectors.toList());
    }

    @NonNull
    private DescribeImagesRequest makeDescribeImagesRequest() throws AmazonClientException {
        List<String> imageIds = Util.fixEmptyAndTrim(ami) == null ?
            Collections.emptyList() :
            Collections.singletonList(ami);
        List<String> owners = makeImageAttributeList(amiOwners);
        List<String> users = makeImageAttributeList(amiUsers);
        List<Filter> filters = EC2Filter.toFilterList(amiFilters);

        // Raise an exception if there were no search attributes.
        // This is legal but not what anyone wants - it will
        // launch random recently created public AMIs.
        int numAttrs = Stream.of(imageIds, owners, users, filters)
            .collect(Collectors.summingInt(List::size));
        if (numAttrs == 0) {
            throw new AmazonClientException("Neither AMI ID nor AMI search attributes provided");
        }

        return new DescribeImagesRequest()
            .withImageIds(imageIds)
            .withOwners(owners)
            .withExecutableUsers(users)
            .withFilters(filters);
    }

    @NonNull
    private Image getImage() throws AmazonClientException {
        DescribeImagesRequest request = makeDescribeImagesRequest();

        LOGGER.info("Getting image for request " + request);
        List<Image> images = getParent().connect().describeImages(request).getImages();
        if (images.isEmpty()) {
            throw new AmazonClientException("Unable to find image for request " + request);
        }

        // Sort in reverse by creation date to get latest image
        images.sort(Comparator.comparing(Image::getCreationDate).reversed());
        return images.get(0);
    }


    private void setupCustomDeviceMapping(List<BlockDeviceMapping> deviceMappings) {
        if (StringUtils.isNotBlank(customDeviceMapping)) {
            deviceMappings.addAll(DeviceMappingParser.parse(customDeviceMapping));
        }
    }

    private boolean isUsingDynamicSshKeys() {
        if (parent != null) {
            String id = parent.getSshKeysCredentialsId();
            if (id == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Provision a new agent for an EC2 spot instance to call back to Jenkins
     */
    private List<EC2AbstractSlave> provisionSpot(Image image, int number, EnumSet<ProvisionOptions> provisionOptions)
            throws IOException {
        LOGGER.fine(() -> "attempt to provision " + number + " spot instances");

        if (!spotConfig.useBidPrice) {
            return provisionOndemand(image, 1, provisionOptions, true, spotConfig.getFallbackToOndemand());
        }

        AmazonEC2 ec2 = getParent().connect();
        String imageId = image.getImageId();
        LaunchSpecification launchSpecification = null;
        try {
            LOGGER.info("Launching " + imageId + " for template " + description);

            RequestSpotInstancesRequest spotRequest = new RequestSpotInstancesRequest();

            // Validate spot bid before making the request
            if (getSpotMaxBidPrice() == null) {
                throw new AmazonClientException("Invalid Spot price specified: " + getSpotMaxBidPrice());
            }

            spotRequest.setSpotPrice(getSpotMaxBidPrice());
            spotRequest.setInstanceCount(number);

            launchSpecification = new LaunchSpecification();

            launchSpecification.setImageId(imageId);
            launchSpecification.setInstanceType(type);
            launchSpecification.setEbsOptimized(ebsOptimized);
            launchSpecification.setMonitoringEnabled(monitoring);

            if (StringUtils.isNotBlank(getZone())) {
                SpotPlacement placement = new SpotPlacement(getZone());
                launchSpecification.setPlacement(placement);
            }

            InstanceNetworkInterfaceSpecification net = new InstanceNetworkInterfaceSpecification();
            String subnetId = chooseSubnetId();
            if (StringUtils.isNotBlank(subnetId)) {
                net.setSubnetId(subnetId);

                /*
                 * If we have a subnet ID then we can only use VPC security groups
                 */
                if (!securityGroupSet.isEmpty()) {
                    List<String> groupIds = getEc2SecurityGroups(ec2);
                    if (!groupIds.isEmpty()) {
                        net.setGroups(groupIds);
                    }
                }
            } else {
                if (!securityGroupSet.isEmpty()) {
                    List<String> groupIds = getSecurityGroupsBy("group-name", securityGroupSet, ec2)
                                                    .getSecurityGroups()
                                                    .stream().map(SecurityGroup::getGroupId)
                                                    .collect(Collectors.toList());
                    net.setGroups(groupIds);
                }
            }

            String userDataString = Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));

            launchSpecification.setUserData(userDataString);

            KeyPair keyPair = null;
            if (!isUsingDynamicSshKeys()) {
                // there is a global/static ssh keyPair configured, so use that
                LOGGER.fine(() -> "static ssh credential is configured, resolving keyname to use in spot launch request");
                keyPair = getParent().resolveKeyPair();
                if (keyPair != null) {
                    launchSpecification.setKeyName(keyPair.getKeyName());
                    LOGGER.fine(() -> "Set static keypair for spot instance request");
                }
            } else {
                LOGGER.fine(() -> "creating keypair for spot instance request");
                keyPair = EC2Cloud.createKeyPair(ec2);
                launchSpecification.setKeyName(keyPair.getKeyName());
                LOGGER.fine("set dynamic keypair for spot instance request with fingerprint " + keyPair.getKeyFingerprint());

            }
            launchSpecification.setInstanceType(type.toString());

            net.setAssociatePublicIpAddress(getAssociatePublicIp());
            net.setDeviceIndex(0);
            launchSpecification.withNetworkInterfaces(net);

            HashSet<Tag> instTags = buildTags(EC2Cloud.EC2_SLAVE_TYPE_SPOT);

            if (StringUtils.isNotBlank(getIamInstanceProfile())) {
                launchSpecification.setIamInstanceProfile(new IamInstanceProfileSpecification().withArn(getIamInstanceProfile()));
            }

            setupBlockDeviceMappings(image, launchSpecification.getBlockDeviceMappings());

            spotRequest.setLaunchSpecification(launchSpecification);

            if (getSpotBlockReservationDuration() != 0) {
                spotRequest.setBlockDurationMinutes(getSpotBlockReservationDuration() * 60);
            }

            RequestSpotInstancesResult reqResult;
            try {
                // Make the request for a new Spot instance
                reqResult = ec2.requestSpotInstances(spotRequest);
            } catch (AmazonEC2Exception e) {
                // cleanup the keypair, if one was created (a new one will be created for the ondemand instance)
                cleanUpSshKeyPairIfNeeded(launchSpecification.getKeyName());
                if (spotConfig.getFallbackToOndemand() && e.getErrorCode().equals("MaxSpotInstanceCountExceeded")) {
                    logProvisionInfo("There is no spot capacity available matching your request, falling back to on-demand instance.");
                    return provisionOndemand(image, number, provisionOptions);
                } else {
                    throw e;
                }
            }

            List<SpotInstanceRequest> reqInstances = reqResult.getSpotInstanceRequests();
            if (reqInstances.isEmpty()) {
                throw new AmazonClientException("No spot instances found");
            }

            List<EC2AbstractSlave> slaves = new ArrayList<>(reqInstances.size());
            for(SpotInstanceRequest spotInstReq : reqInstances) {
                if (spotInstReq == null) {
                    throw new AmazonClientException("Spot instance request is null");
                }
                String slaveName = spotInstReq.getSpotInstanceRequestId();

                if (spotConfig.getFallbackToOndemand()) {
                    for (int i = 0; i < 2 && spotInstReq.getStatus().getCode().equals("pending-evaluation"); i++) {
                        LOGGER.info("Spot request " + slaveName + " is still pending evaluation");
                        Thread.sleep(5000);
                        LOGGER.info("Fetching info about spot request " + slaveName);
                        DescribeSpotInstanceRequestsRequest describeRequest = new DescribeSpotInstanceRequestsRequest().withSpotInstanceRequestIds(slaveName);
                        spotInstReq = ec2.describeSpotInstanceRequests(describeRequest).getSpotInstanceRequests().get(0);
                    }

                    List<String> spotRequestBadCodes = Arrays.asList("capacity-not-available", "capacity-oversubscribed", "price-too-low");
                    if (spotRequestBadCodes.contains(spotInstReq.getStatus().getCode())) {
                        LOGGER.info("There is no spot capacity available matching your request, falling back to on-demand instance.");
                        List<String> requestsToCancel = reqInstances.stream().map(SpotInstanceRequest::getSpotInstanceRequestId).collect(Collectors.toList());
                        CancelSpotInstanceRequestsRequest cancelRequest = new CancelSpotInstanceRequestsRequest(requestsToCancel);
                        ec2.cancelSpotInstanceRequests(cancelRequest);
                        // cleanup the keypair, if one was created (a new one will be createed)
                        cleanUpSshKeyPairIfNeeded(launchSpecification.getKeyName());
                        return provisionOndemand(image, number, provisionOptions);
                    }
                }

                // Now that we have our Spot request, we can set tags on it
                updateRemoteTags(ec2, instTags, "InvalidSpotInstanceRequestID.NotFound", spotInstReq.getSpotInstanceRequestId());

                // That was a remote request - we should also update our local instance data
                spotInstReq.setTags(instTags);

                LOGGER.info("Spot instance id in provision: " + spotInstReq.getSpotInstanceRequestId());

                slaves.add(newSpotSlave(spotInstReq, keyPair));
            }

            return slaves;

        } catch (FormException e) {
            if (launchSpecification != null) {
                cleanUpSshKeyPairIfNeeded(launchSpecification.getKeyName());
            }
            throw new AssertionError(); // we should have discovered all configuration issues up front
        } catch (InterruptedException e) {
            cleanUpSshKeyPairIfNeeded(launchSpecification.getKeyName());
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void setupBlockDeviceMappings(Image image, List<BlockDeviceMapping> blockDeviceMappings) {
        setupRootDevice(image, blockDeviceMappings);
        if (useEphemeralDevices) {
            setupEphemeralDeviceMapping(image, blockDeviceMappings);
        } else {
            setupCustomDeviceMapping(blockDeviceMappings);
        }
    }

    // attempts to delete the specified keypair in ec2 IFF the instance is configured to use dynamic ssh keys
    private void cleanUpSshKeyPairIfNeeded(String keyname) throws IOException {
        LOGGER.fine(() -> "removed ssh keypair fromm failed provision attempt");
        if ((parent.resolveKeyPair() == null) && (keyname != null)) {
            //this instance is using dynamic ssh keys
            AmazonEC2 ec2 = getParent().connect();
            ec2.deleteKeyPair(new DeleteKeyPairRequest().withKeyName(keyname));
            LOGGER.fine(() -> "EC2 instance delete key pair request sent for keypair " + keyname);
        } else {
            LOGGER.fine(() -> "No dynamic keypair to delete for  because a static key has been configured");
        }
    }

    private HashSet<Tag> buildTags(String slaveType) {
        boolean hasCustomTypeTag = false;
        boolean hasJenkinsServerUrlTag = false;
        HashSet<Tag> instTags = new HashSet<>();
        if (tags != null && !tags.isEmpty()) {
            for (EC2Tag t : tags) {
                instTags.add(new Tag(t.getName(), t.getValue()));
                if (StringUtils.equals(t.getName(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)) {
                    hasCustomTypeTag = true;
                }
                if (StringUtils.equals(t.getName(), EC2Tag.TAG_NAME_JENKINS_SERVER_URL)) {
                    hasJenkinsServerUrlTag = true;
                }
            }
        }
        if (!hasCustomTypeTag) {
            instTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, EC2Cloud.getSlaveTypeTagValue(
                    slaveType, description)));
        }
        JenkinsLocationConfiguration jenkinsLocation = JenkinsLocationConfiguration.get();
        if (!hasJenkinsServerUrlTag && jenkinsLocation.getUrl() != null) {
            instTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SERVER_URL, jenkinsLocation.getUrl()));
        }
        return instTags;
    }

    protected EC2OndemandSlave newOndemandSlave(InstanceInfo info) throws FormException, IOException {
        Instance instance = info.getInstance();
        EC2AgentConfig.OnDemand config = new EC2AgentConfig.OnDemandBuilder()
            .withName(getSlaveName(instance.getInstanceId()))
            .withInstanceId(instance.getInstanceId())
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
            .withPublicDNS(instance.getPublicDnsName())
            .withPrivateDNS(instance.getPrivateDnsName())
            .withTags(EC2Tag.fromAmazonTags(instance.getTags()))
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
            .withKeyPair(info.getKeypair())
            .build();
        return EC2AgentFactory.getInstance().createOnDemandAgent(config);
    }

    protected EC2SpotSlave newSpotSlave(SpotInstanceRequest sir, KeyPair keyPair) throws FormException, IOException {
        EC2AgentConfig.Spot config = new EC2AgentConfig.SpotBuilder()
            .withName(getSlaveName(sir.getSpotInstanceRequestId()))
            .withSpotInstanceRequestId(sir.getSpotInstanceRequestId())
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
            .withTags(EC2Tag.fromAmazonTags(sir.getTags()))
            .withCloudName(parent.name)
            .withLaunchTimeout(getLaunchTimeout())
            .withAmiType(amiType)
            .withConnectionStrategy(connectionStrategy)
            .withMaxTotalUses(maxTotalUses)
            .withKeyPair(keyPair)
            .build();
        return EC2AgentFactory.getInstance().createSpotAgent(config);
    }

    /**
     * Get a KeyPair from the configured information for the agent template
     *
     * returns null if no global private key has been configured
     */
    @CheckForNull
    private KeyPair getKeyPair(AmazonEC2 ec2) throws IOException, AmazonClientException {
        EC2PrivateKey ec2PrivateKey = getParent().resolvePrivateKey();

        if (ec2PrivateKey != null) {
            // there is a static ssh key configured, make sure it is valid
            LOGGER.fine("using static ssh key from configuration with fingerprint " + ec2PrivateKey.getFingerprint());
            KeyPair keyPair = ec2PrivateKey.find(ec2);
            if (keyPair == null) {
                throw new AmazonClientException("No matching keypair found on EC2 for key " + ec2PrivateKey.getFingerprint() + ". Is the EC2 private key a valid one?");
            }
            return keyPair;
        }
        return null;
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
    private void updateRemoteTags(AmazonEC2 ec2, Collection<Tag> instTags, String catchErrorCode, String... params)
            throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            try {
                CreateTagsRequest tagRequest = new CreateTagsRequest();
                tagRequest.withResources(params).setTags(instTags);
                ec2.createTags(tagRequest);
                break;
            } catch (AmazonServiceException e) {
                if (e.getErrorCode().equals(catchErrorCode)) {
                    Thread.sleep(5000);
                    continue;
                }
                LOGGER.log(Level.SEVERE, e.getErrorMessage(), e);
            }
        }
    }

    /**
     * Get a list of security group ids for the agent
     */
    private List<String> getEc2SecurityGroups(AmazonEC2 ec2) throws AmazonClientException {
        List<String> groupIds = new ArrayList<>();

        DescribeSecurityGroupsResult groupResult = getSecurityGroupsBy("group-name", securityGroupSet, ec2);
        if (groupResult.getSecurityGroups().size() == 0) {
            groupResult = getSecurityGroupsBy("group-id", securityGroupSet, ec2);
        }

        for (SecurityGroup group : groupResult.getSecurityGroups()) {
            if (group.getVpcId() != null && !group.getVpcId().isEmpty()) {
                List<Filter> filters = new ArrayList<>();
                filters.add(new Filter("vpc-id").withValues(group.getVpcId()));
                filters.add(new Filter("state").withValues("available"));
                filters.add(new Filter("subnet-id").withValues(getCurrentSubnetId()));

                DescribeSubnetsRequest subnetReq = new DescribeSubnetsRequest();
                subnetReq.withFilters(filters);
                DescribeSubnetsResult subnetResult = ec2.describeSubnets(subnetReq);

                List<Subnet> subnets = subnetResult.getSubnets();
                if (subnets != null && !subnets.isEmpty()) {
                    groupIds.add(group.getGroupId());
                }
            }
        }

        if (securityGroupSet.size() != groupIds.size()) {
            throw new AmazonClientException("Security groups must all be VPC security groups to work in a VPC context");
        }

        return groupIds;
    }

    private DescribeSecurityGroupsResult getSecurityGroupsBy(String filterName, Set<String> filterValues, AmazonEC2 ec2) {
        DescribeSecurityGroupsRequest groupReq = new DescribeSecurityGroupsRequest();
        groupReq.withFilters(new Filter(filterName).withValues(filterValues));
        return ec2.describeSecurityGroups(groupReq);
    }

    /**
     * Provisions a new EC2 agent based on the currently running instance on EC2, instead of starting a new one.
     */
    public EC2AbstractSlave attach(String instanceId, TaskListener listener) throws Exception {
        AmazonEC2 ec2 = getParent().connect();

        if (isUsingDynamicSshKeys()) {
            throw new Exception("not allowed when using dynamic ssh keypair management");
        }
        try {
            LOGGER.info("Attaching to " + instanceId);
            DescribeInstancesRequest request = new DescribeInstancesRequest();
            request.setInstanceIds(Collections.singletonList(instanceId));
            Instance inst = ec2.describeInstances(request).getReservations().get(0).getInstances().get(0);
            return newOndemandSlave(new InstanceInfo(inst, null));
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

         // 1.43 new parameters
        if (connectionStrategy == null )  {
            connectionStrategy = ConnectionStrategy.backwardsCompatible(usePrivateDnsName, connectUsingPublicIp, associatePublicIp);
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
            javaPath = DEFAULT_JAVA_PATH;
        }

        return this;
    }

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

    public Secret getAdminPassword() {
        return amiType.isWindows() ? ((WindowsData) amiType).getPassword() : Secret.fromString("");
    }

    public boolean isUseHTTPS() {
        return amiType.isWindows() && ((WindowsData) amiType).isUseHTTPS();
    }

    /**
     *
     * @param ec2
     * @param allSubnets if true, uses all subnets defined for this SlaveTemplate as the filter, else will only use the current subnet
     * @return DescribeInstancesResult of DescribeInstanceRequst constructed from this SlaveTemplate's configs
     */
    DescribeInstancesResult getDescribeInstanceResult(AmazonEC2 ec2, boolean allSubnets) throws IOException {
        HashMap<RunInstancesRequest, List<Filter>> runInstancesRequestFilterMap = makeRunInstancesRequestAndFilters(getImage(), 1, ec2, false);
        Map.Entry<RunInstancesRequest, List<Filter>> entry = runInstancesRequestFilterMap.entrySet().iterator().next();
        List<Filter> diFilters = entry.getValue();

        if (allSubnets) {
            /* remove any existing subnet-id filters */
            List<Filter> rmvFilters = new ArrayList<>();
            for (Filter f : diFilters) {
                if (f.getName().equals("subnet-id")) {
                    rmvFilters.add(f);
                }
            }
            for (Filter f : rmvFilters) {
                diFilters.remove(f);
            }

            /* Add filter using all subnets defined for this SlaveTemplate */
            Filter subnetFilter = new Filter("subnet-id");
            subnetFilter.setValues(Arrays.asList(getSubnetId().split(EC2_RESOURCE_ID_DELIMETERS)));
            diFilters.add(subnetFilter);
        }

        //check to see if we need to create ssh keys
        if (isUsingDynamicSshKeys()){
            /* remove any existing keypair-id filters */
            List<Filter> rmvFilters = new ArrayList<>();
            for (Filter f : diFilters) {
                if (f.getName().equals("keypair-id")) {
                    rmvFilters.add(f);
                }
            }
            for (Filter f : rmvFilters) {
                diFilters.remove(f);
            }
        }

        DescribeInstancesRequest diRequest = new DescribeInstancesRequest().withFilters(diFilters);
        return ec2.describeInstances(diRequest);
    }

    public boolean isAllowSelfSignedCertificate() {
        return amiType.isWindows() && ((WindowsData) amiType).isAllowSelfSignedCertificate();
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
            if (p != null)
                return p;
            Descriptor slaveDescriptor = Jenkins.get().getDescriptor(EC2OndemandSlave.class);
            if (slaveDescriptor != null) {
                p = slaveDescriptor.getHelpFile(fieldName);
                if (p != null)
                    return p;
            }
            slaveDescriptor = Jenkins.get().getDescriptor(EC2SpotSlave.class);
            if (slaveDescriptor != null)
                return slaveDescriptor.getHelpFile(fieldName);
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

        /***
         * Check that the AMI requested is available in the cloud and can be used.
         */
        @RequirePOST
        public FormValidation doValidateAmi(@QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId, @QueryParameter String ec2endpoint,
                @QueryParameter String region, final @QueryParameter String ami, @QueryParameter String roleArn,
                @QueryParameter String roleSessionName) throws IOException {
            checkPermission(EC2Cloud.PROVISION);
            AWSCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
            AmazonEC2 ec2;
            if (region != null) {
                ec2 = AmazonEC2Factory.getInstance().connect(credentialsProvider, AmazonEC2Cloud.getEc2EndpointUrl(region));
            } else {
                ec2 = AmazonEC2Factory.getInstance().connect(credentialsProvider, new URL(ec2endpoint));
            }
            try {
                Image img = CloudHelper.getAmiImage(ec2, ami);
                if (img == null) {
                    return FormValidation.error("No such AMI, or not usable with this accessId: " + ami);
                }
                String ownerAlias = img.getImageOwnerAlias();
                return FormValidation.ok(img.getImageLocation() + (ownerAlias != null ? " by " + ownerAlias : ""));
            } catch (AmazonClientException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        private void checkPermission(Permission p) {
            final EC2Cloud ancestorObject = Stapler.getCurrentRequest().findAncestorObject(EC2Cloud.class);
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
            if (value == null || value.trim().isEmpty())
                return FormValidation.ok();
            try {
                int val = Integer.parseInt(value);
                if (val >= -59)
                    return FormValidation.ok();
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("Idle Termination time must be a greater than -59 (or null)");
        }

        @POST
        public FormValidation doCheckMaxTotalUses(@QueryParameter String value) {
            try {
                int val = Integer.parseInt(value);
                if (val >= -1)
                    return FormValidation.ok();
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("Maximum Total Uses must be greater or equal to -1");
        }

        @POST
        public FormValidation doCheckMinimumNumberOfInstances(@QueryParameter String value, @QueryParameter String instanceCapStr) {
            if (value == null || value.trim().isEmpty())
                return FormValidation.ok();
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
                        return FormValidation
                          .error("Minimum number of instances must not be larger than AMI Instance Cap %d",
                            instanceCap);
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
        public FormValidation doCheckMonday(@QueryParameter boolean monday,
                                            @QueryParameter boolean tuesday,
                                            @QueryParameter boolean wednesday,
                                            @QueryParameter boolean thursday,
                                            @QueryParameter boolean friday,
                                            @QueryParameter boolean saturday,
                                            @QueryParameter boolean sunday) {
            if (!(monday || tuesday || wednesday || thursday || friday || saturday || sunday)) {
                return FormValidation.warning("At least one day should be checked or minimum number of instances won't be active");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckMinimumNumberOfSpareInstances(@QueryParameter String value, @QueryParameter String instanceCapStr) {
            if (value == null || value.trim().isEmpty())
                return FormValidation.ok();
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
                        return FormValidation
                          .error("Minimum number of spare instances must not be larger than AMI Instance Cap %d",
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
            if (value == null || value.trim().isEmpty())
                return FormValidation.ok();
            try {
                int val = Integer.parseInt(value);
                if (val > 0)
                    return FormValidation.ok();
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("InstanceCap must be a non-negative integer (or null)");
        }

        /*
         * Validate the Spot Block Duration to be between 0 & 6 hours as specified in the AWS API
         */
        @POST
        public FormValidation doCheckSpotBlockReservationDurationStr(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty())
                return FormValidation.ok();
            try {
                int val = Integer.parseInt(value);
                if (val >= 0 && val <= 6)
                    return FormValidation.ok();
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("Spot Block Reservation Duration must be an integer between 0 & 6");
        }

        @POST
        public FormValidation doCheckLaunchTimeoutStr(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty())
                return FormValidation.ok();
            try {
                int val = Integer.parseInt(value);
                if (val >= 0)
                    return FormValidation.ok();
            } catch (NumberFormatException nfe) {
            }
            return FormValidation.error("Launch Timeout must be a non-negative integer (or null)");
        }

        @RequirePOST
        public ListBoxModel doFillZoneItems(@QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId, @QueryParameter String region, @QueryParameter String roleArn,
                @QueryParameter String roleSessionName)
                throws IOException, ServletException {
            checkPermission(EC2Cloud.PROVISION);
            AWSCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
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
            Stream<HostKeyVerificationStrategyEnum> filteredStream = stream.filter(v -> v.name().equals(hostKeyVerificationStrategy));
            Optional<HostKeyVerificationStrategyEnum> matched = filteredStream.findFirst();
            Optional<FormValidation> okResult = matched.map(s -> FormValidation.ok());
            return okResult.orElse(FormValidation.error(String.format("Could not find selected host key verification (%s)", hostKeyVerificationStrategy)));
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
        public ListBoxModel doFillEbsEncryptRootVolumeItems(@QueryParameter String ebsEncryptRootVolume ) {
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
            Stream<EbsEncryptRootVolume> filteredStream = stream.filter(v -> v.name().equals(ebsEncryptRootVolume));
            Optional<EbsEncryptRootVolume> matched = filteredStream.findFirst();
            Optional<FormValidation> okResult = matched.map(s -> FormValidation.ok());
            return okResult.orElse(FormValidation.error(String.format("Could not find selected option (%s)", ebsEncryptRootVolume)));
        }
    }
}
