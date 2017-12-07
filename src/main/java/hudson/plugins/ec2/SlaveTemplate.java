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

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.slaves.iterators.api.NodeIterator;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;

import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.model.Descriptor.FormException;
import hudson.model.labels.LabelAtom;
import hudson.plugins.ec2.util.DeviceMappingParser;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

/**
 * Template of {@link EC2AbstractSlave} to launch.
 *
 * @author Kohsuke Kawaguchi
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {
    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());

    public String ami;

    public final String description;

    public final String zone;

    public final SpotConfiguration spotConfig;

    public final String securityGroups;

    public final String remoteFS;

    public final InstanceType type;

    public final boolean ebsOptimized;

    public final String labels;

    public final Node.Mode mode;

    public final String initScript;

    public final String tmpDir;

    public final String userData;

    public final String numExecutors;

    public final String remoteAdmin;

    public final String jvmopts;

    public final String subnetId;

    public final String idleTerminationMinutes;

    public final String iamInstanceProfile;

    public final boolean deleteRootOnTermination;

    public final boolean useEphemeralDevices;

    public final String customDeviceMapping;

    public int instanceCap;

    public final boolean stopOnTerminate;

    private final List<EC2Tag> tags;

    public final boolean usePrivateDnsName;

    public final boolean associatePublicIp;

    protected transient EC2Cloud parent;

    public final boolean useDedicatedTenancy;

    public AMITypeData amiType;

    public int launchTimeout;

    public boolean connectBySSHProcess;

    public final boolean connectUsingPublicIp;

    private transient/* almost final */Set<LabelAtom> labelSet;

    private transient/* almost final */Set<String> securityGroupSet;

    /*
     * Necessary to handle reading from old configurations. The UnixData object is created in readResolve()
     */
    @Deprecated
    public transient String sshPort;

    @Deprecated
    public transient String rootCommandPrefix;

    @Deprecated
    public transient String slaveCommandPrefix;

    @DataBoundConstructor
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS,
            InstanceType type, boolean ebsOptimized, String labelString, Node.Mode mode, String description, String initScript,
            String tmpDir, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts,
            boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes,
            boolean usePrivateDnsName, String instanceCapStr, String iamInstanceProfile, boolean deleteRootOnTermination,
            boolean useEphemeralDevices, boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp,
            String customDeviceMapping, boolean connectBySSHProcess, boolean connectUsingPublicIp) {

        if(StringUtils.isNotBlank(remoteAdmin) || StringUtils.isNotBlank(jvmopts) || StringUtils.isNotBlank(tmpDir)){
            LOGGER.log(Level.FINE, "As remoteAdmin, jvmopts or tmpDir is not blank, we must ensure the user has RUN_SCRIPTS rights.");
            Jenkins j = Jenkins.getInstance();
            if(j != null){
                j.checkPermission(Jenkins.RUN_SCRIPTS);
            }
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
        this.mode = mode;
        this.description = description;
        this.initScript = initScript;
        this.tmpDir = tmpDir;
        this.userData = userData;
        this.numExecutors = Util.fixNull(numExecutors).trim();
        this.remoteAdmin = remoteAdmin;
        this.jvmopts = jvmopts;
        this.stopOnTerminate = stopOnTerminate;
        this.subnetId = subnetId;
        this.tags = tags;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.usePrivateDnsName = usePrivateDnsName;
        this.associatePublicIp = associatePublicIp;
        this.connectUsingPublicIp = connectUsingPublicIp;
        this.useDedicatedTenancy = useDedicatedTenancy;
        this.connectBySSHProcess = connectBySSHProcess;

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

        readResolve(); // initialize
    }

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
            String slaveCommandPrefix, String jvmopts, boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes,
            boolean usePrivateDnsName, String instanceCapStr, String iamInstanceProfile, boolean useEphemeralDevices,
            String launchTimeoutStr) {
        this(ami, zone, spotConfig, securityGroups, remoteFS, type, ebsOptimized, labelString, mode, description, initScript,
                tmpDir, userData, numExecutors, remoteAdmin, new UnixData(rootCommandPrefix, slaveCommandPrefix, sshPort),
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
        return description + " (" + ami + ")";
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
            return new HashSet<String>(Arrays.asList(securityGroups.split("\\s*,\\s*")));
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
            return Integer.parseInt(sshPort);
        } catch (NumberFormatException e) {
            return 22;
        }
    }

    public String getRemoteAdmin() {
        return remoteAdmin;
    }

    public String getRootCommandPrefix() {
        return amiType.isUnix() ? ((UnixData) amiType).getRootCommandPrefix() : "";
    }

    public String getSlaveCommandPrefix() {
        return amiType.isUnix() ? ((UnixData) amiType).getSlaveCommandPrefix() : "";
    }


    public String getSubnetId() {
        return subnetId;
    }

    public boolean getAssociatePublicIp() {
        return associatePublicIp;
    }

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

    public boolean getUseDedicatedTenancy() {
        return useDedicatedTenancy;
    }

    public Set<LabelAtom> getLabelSet() {
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

    public int getInstanceCap() {
        return instanceCap;
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
        return SpotConfiguration.normalizeBid(spotConfig.spotMaxBidPrice);
    }

    public String getIamInstanceProfile() {
        return iamInstanceProfile;
    }

    @Override
    public String toString() {
        return "SlaveTemplate{" +
                "ami='" + ami + '\'' +
                ", labels='" + labels + '\'' +
                '}';
    }

    public enum ProvisionOptions { ALLOW_CREATE, FORCE_CREATE }

    /**
     * Provisions a new EC2 slave or starts a previously stopped on-demand instance.
     *
     * @return always non-null. This needs to be then added to {@link Hudson#addNode(Node)}.
     */
    public List<EC2AbstractSlave> provision(int number, EnumSet<ProvisionOptions> provisionOptions) throws AmazonClientException, IOException {
        if (this.spotConfig != null) {
            if (provisionOptions.contains(ProvisionOptions.ALLOW_CREATE) || provisionOptions.contains(ProvisionOptions.FORCE_CREATE))
                return provisionSpot(number);
            return null;
        }
        return provisionOndemand(number, provisionOptions);
    }

    /**
     * Safely we can pickup only instance that is not known by Jenkins at all.
     */
    private boolean checkInstance(Instance instance) {
        for (EC2AbstractSlave node : NodeIterator.nodes(EC2AbstractSlave.class)) {
            if (node.getInstanceId().equals(instance.getInstanceId())) {
                logInstanceCheck(instance, ". false - found existing corresponding Jenkins slave: " + node.getInstanceId());
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

    /**
     * Provisions an On-demand EC2 slave by launching a new instance or starting a previously-stopped instance.
     */
    private List<EC2AbstractSlave> provisionOndemand(int number, EnumSet<ProvisionOptions> provisionOptions) throws AmazonClientException, IOException {
        AmazonEC2 ec2 = getParent().connect();

        logProvisionInfo("Considering launching");

        RunInstancesRequest riRequest = new RunInstancesRequest(ami, 1, number).withInstanceType(type);
        riRequest.setEbsOptimized(ebsOptimized);

        setupBlockDeviceMappings(riRequest.getBlockDeviceMappings());

        if(stopOnTerminate){
            riRequest.setInstanceInitiatedShutdownBehavior(ShutdownBehavior.Stop);
            logProvisionInfo("Setting Instance Initiated Shutdown Behavior : ShutdownBehavior.Stop");
        }else{
            riRequest.setInstanceInitiatedShutdownBehavior(ShutdownBehavior.Terminate);
            logProvisionInfo("Setting Instance Initiated Shutdown Behavior : ShutdownBehavior.Terminate");
        }

        List<Filter> diFilters = new ArrayList<Filter>();
        diFilters.add(new Filter("image-id").withValues(ami));
        diFilters.add(new Filter("instance-type").withValues(type.toString()));

        KeyPair keyPair = getKeyPair(ec2);
        riRequest.setUserData(Base64.encodeBase64String(userData.getBytes(StandardCharsets.UTF_8)));
        riRequest.setKeyName(keyPair.getKeyName());
        diFilters.add(new Filter("key-name").withValues(keyPair.getKeyName()));


        if (StringUtils.isNotBlank(getZone())) {
            Placement placement = new Placement(getZone());
            if (getUseDedicatedTenancy()) {
                placement.setTenancy("dedicated");
            }
            riRequest.setPlacement(placement);
            diFilters.add(new Filter("availability-zone").withValues(getZone()));
        }

        InstanceNetworkInterfaceSpecification net = new InstanceNetworkInterfaceSpecification();
        if (StringUtils.isNotBlank(getSubnetId())) {
            if (getAssociatePublicIp()) {
                net.setSubnetId(getSubnetId());
            } else {
                riRequest.setSubnetId(getSubnetId());
            }

            diFilters.add(new Filter("subnet-id").withValues(getSubnetId()));

            /*
             * If we have a subnet ID then we can only use VPC security groups
             */
            if (!securityGroupSet.isEmpty()) {
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
            /* No subnet: we can use standard security groups by name */
            riRequest.setSecurityGroups(securityGroupSet);
            if (!securityGroupSet.isEmpty()) {
                diFilters.add(new Filter("instance.group-name").withValues(securityGroupSet));
            }
        }

        if (getAssociatePublicIp()) {
            net.setAssociatePublicIpAddress(true);
            net.setDeviceIndex(0);
            riRequest.withNetworkInterfaces(net);
        }

        HashSet<Tag> instTags = buildTags(EC2Cloud.EC2_SLAVE_TYPE_DEMAND);
        for (Tag tag : instTags) {
            diFilters.add(new Filter("tag:" + tag.getKey()).withValues(tag.getValue()));
        }

        DescribeInstancesRequest diRequest = new DescribeInstancesRequest();
        diRequest.setFilters(diFilters);

        logProvisionInfo("Looking for existing instances with describe-instance: " + diRequest);

        DescribeInstancesResult diResult = ec2.describeInstances(diRequest);
        List<Instance> orphans = findOrphans(diResult, number);

        if (orphans.isEmpty() && !provisionOptions.contains(ProvisionOptions.FORCE_CREATE) &&
                !provisionOptions.contains(ProvisionOptions.ALLOW_CREATE)) {
            logProvisionInfo("No existing instance found - but cannot create new instance");
            return null;
        }

        wakeOrphansUp(ec2, orphans);

        if (orphans.size() == number) {
            return toSlaves(orphans);
        }

        riRequest.setMaxCount(number - orphans.size());

        if (StringUtils.isNotBlank(getIamInstanceProfile())) {
            riRequest.setIamInstanceProfile(new IamInstanceProfileSpecification().withArn(getIamInstanceProfile()));
        }

        TagSpecification tagSpecification = new TagSpecification();
        tagSpecification.setResourceType(ResourceType.Instance);
        tagSpecification.setTags(instTags);
        Set<TagSpecification> tagSpecifications =  Collections.singleton(tagSpecification);
        riRequest.setTagSpecifications(tagSpecifications);

        // Have to create a new instance
        List<Instance> newInstances = ec2.runInstances(riRequest).getReservation().getInstances();

        if (newInstances.isEmpty()) {
            logProvisionInfo("No new instances were created");
        }

        newInstances.addAll(orphans);

        return toSlaves(newInstances);
    }

    private void wakeOrphansUp(AmazonEC2 ec2, List<Instance> orphans) {
        List<String> instances = new ArrayList<>();
        for(Instance instance : orphans) {
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

    private List<EC2AbstractSlave> toSlaves(List<Instance> newInstances) throws IOException {
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

    private List<Instance> findOrphans(DescribeInstancesResult diResult, int number) {
        List<Instance> orphans = new ArrayList<>();
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
                    orphans.add(instance);
                    count++;
                }

                if (count == number) {
                    return orphans;
                }
            }
        }
        return orphans;
    }

    private void setupRootDevice(List<BlockDeviceMapping> deviceMappings) {
        if (deleteRootOnTermination && getImage().getRootDeviceType().equals("ebs")) {
            // get the root device (only one expected in the blockmappings)
            final List<BlockDeviceMapping> rootDeviceMappings = getAmiBlockDeviceMappings();
            BlockDeviceMapping rootMapping = null;
            for (final BlockDeviceMapping deviceMapping : rootDeviceMappings) {
                System.out.println("AMI had " + deviceMapping.getDeviceName());
                System.out.println(deviceMapping.getEbs());
                rootMapping = deviceMapping;
                break;
            }

            // Check if the root device is already in the mapping and update it
            for (final BlockDeviceMapping mapping : deviceMappings) {
                System.out.println("Request had " + mapping.getDeviceName());
                if (rootMapping.getDeviceName().equals(mapping.getDeviceName())) {
                    mapping.getEbs().setDeleteOnTermination(Boolean.TRUE);
                    return;
                }
            }

            // Create a shadow of the AMI mapping (doesn't like reusing rootMapping directly)
            BlockDeviceMapping newMapping = new BlockDeviceMapping().withDeviceName(rootMapping.getDeviceName());
            EbsBlockDevice newEbs = new EbsBlockDevice();
            newEbs.setDeleteOnTermination(Boolean.TRUE);
            newMapping.setEbs(newEbs);
            deviceMappings.add(0, newMapping);
        }
    }

    private List<BlockDeviceMapping> getNewEphemeralDeviceMapping() {

        final List<BlockDeviceMapping> oldDeviceMapping = getAmiBlockDeviceMappings();

        final Set<String> occupiedDevices = new HashSet<String>();
        for (final BlockDeviceMapping mapping : oldDeviceMapping) {

            occupiedDevices.add(mapping.getDeviceName());
        }

        final List<String> available = new ArrayList<String>(
                Arrays.asList("ephemeral0", "ephemeral1", "ephemeral2", "ephemeral3"));

        final List<BlockDeviceMapping> newDeviceMapping = new ArrayList<BlockDeviceMapping>(4);
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

    private void setupEphemeralDeviceMapping(List<BlockDeviceMapping> deviceMappings) {
        // Don't wipe out pre-existing mappings
        deviceMappings.addAll(getNewEphemeralDeviceMapping());
    }

    private List<BlockDeviceMapping> getAmiBlockDeviceMappings() {

        /*
         * AmazonEC2#describeImageAttribute does not work due to a bug
         * https://forums.aws.amazon.com/message.jspa?messageID=231972
         */
        return getImage().getBlockDeviceMappings();
    }

    private Image getImage() {
        DescribeImagesRequest request = new DescribeImagesRequest().withImageIds(ami);
        for (final Image image : getParent().connect().describeImages(request).getImages()) {

            if (ami.equals(image.getImageId())) {

                return image;
            }
        }

        throw new AmazonClientException("Unable to find AMI " + ami);
    }


    private void setupCustomDeviceMapping(List<BlockDeviceMapping> deviceMappings) {
        if (StringUtils.isNotBlank(customDeviceMapping)) {
            deviceMappings.addAll(DeviceMappingParser.parse(customDeviceMapping));
        }
    }

    /**
     * Provision a new slave for an EC2 spot instance to call back to Jenkins
     */
    private List<EC2AbstractSlave> provisionSpot(int number) throws AmazonClientException, IOException {
        AmazonEC2 ec2 = getParent().connect();

        try {
            LOGGER.info("Launching " + ami + " for template " + description);

            KeyPair keyPair = getKeyPair(ec2);

            RequestSpotInstancesRequest spotRequest = new RequestSpotInstancesRequest();

            // Validate spot bid before making the request
            if (getSpotMaxBidPrice() == null) {
                throw new AmazonClientException("Invalid Spot price specified: " + getSpotMaxBidPrice());
            }

            spotRequest.setSpotPrice(getSpotMaxBidPrice());
            spotRequest.setInstanceCount(number);

            LaunchSpecification launchSpecification = new LaunchSpecification();

            launchSpecification.setImageId(ami);
            launchSpecification.setInstanceType(type);
            launchSpecification.setEbsOptimized(ebsOptimized);

            if (StringUtils.isNotBlank(getZone())) {
                SpotPlacement placement = new SpotPlacement(getZone());
                launchSpecification.setPlacement(placement);
            }

            InstanceNetworkInterfaceSpecification net = new InstanceNetworkInterfaceSpecification();
            if (StringUtils.isNotBlank(getSubnetId())) {
                if (getAssociatePublicIp()) {
                    net.setSubnetId(getSubnetId());
                } else {
                    launchSpecification.setSubnetId(getSubnetId());
                }

                /*
                 * If we have a subnet ID then we can only use VPC security groups
                 */
                if (!securityGroupSet.isEmpty()) {
                    List<String> groupIds = getEc2SecurityGroups(ec2);
                    if (!groupIds.isEmpty()) {
                        if (getAssociatePublicIp()) {
                            net.setGroups(groupIds);
                        } else {
                            ArrayList<GroupIdentifier> groups = new ArrayList<GroupIdentifier>();

                            for (String group_id : groupIds) {
                                GroupIdentifier group = new GroupIdentifier();
                                group.setGroupId(group_id);
                                groups.add(group);
                            }
                            if (!groups.isEmpty())
                                launchSpecification.setAllSecurityGroups(groups);
                        }
                    }
                }
            } else {
                /* No subnet: we can use standard security groups by name */
                if (!securityGroupSet.isEmpty()) {
                    launchSpecification.setSecurityGroups(securityGroupSet);
                }
            }

            String userDataString = Base64.encodeBase64String(userData.getBytes(StandardCharsets.UTF_8));

            launchSpecification.setUserData(userDataString);
            launchSpecification.setKeyName(keyPair.getKeyName());
            launchSpecification.setInstanceType(type.toString());

            if (getAssociatePublicIp()) {
                net.setAssociatePublicIpAddress(true);
                net.setDeviceIndex(0);
                launchSpecification.withNetworkInterfaces(net);
            }

            HashSet<Tag> instTags = buildTags(EC2Cloud.EC2_SLAVE_TYPE_SPOT);

            if (StringUtils.isNotBlank(getIamInstanceProfile())) {
                launchSpecification.setIamInstanceProfile(new IamInstanceProfileSpecification().withArn(getIamInstanceProfile()));
            }

            setupBlockDeviceMappings(launchSpecification.getBlockDeviceMappings());

            spotRequest.setLaunchSpecification(launchSpecification);

            // Make the request for a new Spot instance
            RequestSpotInstancesResult reqResult = ec2.requestSpotInstances(spotRequest);

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

                // Now that we have our Spot request, we can set tags on it
                updateRemoteTags(ec2, instTags, "InvalidSpotInstanceRequestID.NotFound", spotInstReq.getSpotInstanceRequestId());

                // That was a remote request - we should also update our local instance data
                spotInstReq.setTags(instTags);

                LOGGER.info("Spot instance id in provision: " + spotInstReq.getSpotInstanceRequestId());

                slaves.add(newSpotSlave(spotInstReq, slaveName));
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

    private void setupBlockDeviceMappings(List<BlockDeviceMapping> blockDeviceMappings) {
        setupRootDevice(blockDeviceMappings);
        if (useEphemeralDevices) {
            setupEphemeralDeviceMapping(blockDeviceMappings);
        } else {
            setupCustomDeviceMapping(blockDeviceMappings);
        }
    }

    private HashSet<Tag> buildTags(String slaveType) {
        boolean hasCustomTypeTag = false;
        HashSet<Tag> instTags = new HashSet<Tag>();
        if (tags != null && !tags.isEmpty()) {
            instTags = new HashSet<Tag>();
            for (EC2Tag t : tags) {
                instTags.add(new Tag(t.getName(), t.getValue()));
                if (StringUtils.equals(t.getName(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)) {
                    hasCustomTypeTag = true;
                }
            }
        }
        if (!hasCustomTypeTag) {
            instTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, EC2Cloud.getSlaveTypeTagValue(
                    slaveType, description)));
        }
        return instTags;
    }

    protected EC2OndemandSlave newOndemandSlave(Instance inst) throws FormException, IOException {
        return new EC2OndemandSlave(inst.getInstanceId(), description, remoteFS, getNumExecutors(), labels, mode, initScript,
                tmpDir, remoteAdmin, jvmopts, stopOnTerminate, idleTerminationMinutes, inst.getPublicDnsName(),
                inst.getPrivateDnsName(), EC2Tag.fromAmazonTags(inst.getTags()), parent.name, usePrivateDnsName,
                useDedicatedTenancy, getLaunchTimeout(), amiType);
    }

    protected EC2SpotSlave newSpotSlave(SpotInstanceRequest sir, String name) throws FormException, IOException {
        return new EC2SpotSlave(name, sir.getSpotInstanceRequestId(), description, remoteFS, getNumExecutors(), mode, initScript,
                tmpDir, labels, remoteAdmin, jvmopts, idleTerminationMinutes, EC2Tag.fromAmazonTags(sir.getTags()), parent.name,
                usePrivateDnsName, getLaunchTimeout(), amiType);
    }

    /**
     * Get a KeyPair from the configured information for the slave template
     */
    private KeyPair getKeyPair(AmazonEC2 ec2) throws IOException, AmazonClientException {
        KeyPair keyPair = parent.getPrivateKey().find(ec2);
        if (keyPair == null) {
            throw new AmazonClientException("No matching keypair found on EC2. Is the EC2 private key a valid one?");
        }
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
     * Get a list of security group ids for the slave
     */
    private List<String> getEc2SecurityGroups(AmazonEC2 ec2) throws AmazonClientException {
        List<String> groupIds = new ArrayList<String>();

        DescribeSecurityGroupsResult groupResult = getSecurityGroupsBy("group-name", securityGroupSet, ec2);
        if (groupResult.getSecurityGroups().size() == 0) {
            groupResult = getSecurityGroupsBy("group-id", securityGroupSet, ec2);
        }

        for (SecurityGroup group : groupResult.getSecurityGroups()) {
            if (group.getVpcId() != null && !group.getVpcId().isEmpty()) {
                List<Filter> filters = new ArrayList<Filter>();
                filters.add(new Filter("vpc-id").withValues(group.getVpcId()));
                filters.add(new Filter("state").withValues("available"));
                filters.add(new Filter("subnet-id").withValues(getSubnetId()));

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
     * Provisions a new EC2 slave based on the currently running instance on EC2, instead of starting a new one.
     */
    public EC2AbstractSlave attach(String instanceId, TaskListener listener) throws AmazonClientException, IOException {
        PrintStream logger = listener.getLogger();
        AmazonEC2 ec2 = getParent().connect();

        try {
            logger.println("Attaching to " + instanceId);
            LOGGER.info("Attaching to " + instanceId);
            DescribeInstancesRequest request = new DescribeInstancesRequest();
            request.setInstanceIds(Collections.singletonList(instanceId));
            Instance inst = ec2.describeInstances(request).getReservations().get(0).getInstances().get(0);
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
        Jenkins.getInstance().checkPermission(Jenkins.RUN_SCRIPTS);

        labelSet = Label.parse(labels);
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
            amiType = new UnixData(rootCommandPrefix, slaveCommandPrefix, sshPort);
        }
        return this;
    }

    public Descriptor<SlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
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

    public Secret getAdminPassword() {
        return amiType.isWindows() ? ((WindowsData) amiType).getPassword() : Secret.fromString("");
    }

    public boolean isUseHTTPS() {
        return amiType.isWindows() && ((WindowsData) amiType).isUseHTTPS();
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {

        @Override
        public String getDisplayName() {
            return null;
        }

        public List<Descriptor<AMITypeData>> getAMITypeDescriptors() {
            return Jenkins.getInstance().<AMITypeData, Descriptor<AMITypeData>> getDescriptorList(AMITypeData.class);
        }

        /**
         * Since this shares much of the configuration with {@link EC2Computer}, check its help page, too.
         */
        @Override
        public String getHelpFile(String fieldName) {
            String p = super.getHelpFile(fieldName);
            if (p == null)
                p = Jenkins.getInstance().getDescriptor(EC2OndemandSlave.class).getHelpFile(fieldName);
            if (p == null)
                p = Jenkins.getInstance().getDescriptor(EC2SpotSlave.class).getHelpFile(fieldName);
            return p;
        }

        @Restricted(NoExternalUse.class)
        public FormValidation doCheckRemoteAdmin(@QueryParameter String value){
            if(StringUtils.isBlank(value) || Jenkins.getInstance().hasPermission(Jenkins.RUN_SCRIPTS)){
                return FormValidation.ok();
            }else{
                return FormValidation.error(Messages.General_MissingPermission());
            }
        }

        @Restricted(NoExternalUse.class)
        public FormValidation doCheckTmpDir(@QueryParameter String value){
            if(StringUtils.isBlank(value) || Jenkins.getInstance().hasPermission(Jenkins.RUN_SCRIPTS)){
                return FormValidation.ok();
            }else{
                return FormValidation.error(Messages.General_MissingPermission());
            }
        }

        @Restricted(NoExternalUse.class)
        public FormValidation doCheckJvmopts(@QueryParameter String value){
            if(StringUtils.isBlank(value) || Jenkins.getInstance().hasPermission(Jenkins.RUN_SCRIPTS)){
                return FormValidation.ok();
            }else{
                return FormValidation.error(Messages.General_MissingPermission());
            }
        }

        /***
         * Check that the AMI requested is available in the cloud and can be used.
         */
        public FormValidation doValidateAmi(@QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId, @QueryParameter String ec2endpoint,
                @QueryParameter String region, final @QueryParameter String ami) throws IOException {
            AWSCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials,
                    credentialsId);
            AmazonEC2 ec2;
            if (region != null) {
                ec2 = EC2Cloud.connect(credentialsProvider, AmazonEC2Cloud.getEc2EndpointUrl(region));
            } else {
                ec2 = EC2Cloud.connect(credentialsProvider, new URL(ec2endpoint));
            }
            if (ec2 != null) {
                try {
                    List<String> images = new LinkedList<String>();
                    images.add(ami);
                    List<String> owners = new LinkedList<String>();
                    List<String> users = new LinkedList<String>();
                    DescribeImagesRequest request = new DescribeImagesRequest();
                    request.setImageIds(images);
                    request.setOwners(owners);
                    request.setExecutableUsers(users);
                    List<Image> img = ec2.describeImages(request).getImages();
                    if (img == null || img.isEmpty()) {
                        // de-registered AMI causes an empty list to be
                        // returned. so be defensive
                        // against other possibilities
                        return FormValidation.error("No such AMI, or not usable with this accessId: " + ami);
                    }
                    String ownerAlias = img.get(0).getImageOwnerAlias();
                    return FormValidation.ok(img.get(0).getImageLocation() + (ownerAlias != null ? " by " + ownerAlias : ""));
                } catch (AmazonClientException e) {
                    return FormValidation.error(e.getMessage());
                }
            } else
                return FormValidation.ok(); // can't test
        }

        public FormValidation doCheckLabelString(@QueryParameter String value, @QueryParameter Node.Mode mode) {
            if (mode == Node.Mode.EXCLUSIVE && (value == null || value.trim().isEmpty())) {
                return FormValidation.warning("You may want to assign labels to this node;"
                        + " it's marked to only run jobs that are exclusively tied to itself or a label.");
            }

            return FormValidation.ok();
        }

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

        public ListBoxModel doFillZoneItems(@QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId, @QueryParameter String region)
                throws IOException, ServletException {
            AWSCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials,
                    credentialsId);
            return EC2AbstractSlave.fillZoneItems(credentialsProvider, region);
        }

        /*
         * Validate the Spot Max Bid Price to ensure that it is a floating point number >= .001
         */
        public FormValidation doCheckSpotMaxBidPrice(@QueryParameter String spotMaxBidPrice) {
            if (SpotConfiguration.normalizeBid(spotMaxBidPrice) != null) {
                return FormValidation.ok();
            }
            return FormValidation.error("Not a correct bid price");
        }

        // Retrieve the availability zones for the region
        private ArrayList<String> getAvailabilityZones(AmazonEC2 ec2) {
            ArrayList<String> availabilityZones = new ArrayList<String>();

            DescribeAvailabilityZonesResult zones = ec2.describeAvailabilityZones();
            List<AvailabilityZone> zoneList = zones.getAvailabilityZones();

            for (AvailabilityZone z : zoneList) {
                availabilityZones.add(z.getZoneName());
            }

            return availabilityZones;
        }

        /*
         * Check the current Spot price of the selected instance type for the selected region
         */
        public FormValidation doCurrentSpotPrice(@QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId, @QueryParameter String region,
                @QueryParameter String type, @QueryParameter String zone) throws IOException, ServletException {

            String cp = "";
            String zoneStr = "";

            // Connect to the EC2 cloud with the access id, secret key, and
            // region queried from the created cloud
            AWSCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials,
                    credentialsId);
            AmazonEC2 ec2 = EC2Cloud.connect(credentialsProvider, AmazonEC2Cloud.getEc2EndpointUrl(region));

            if (ec2 != null) {

                try {
                    // Build a new price history request with the currently
                    // selected type
                    DescribeSpotPriceHistoryRequest request = new DescribeSpotPriceHistoryRequest();
                    // If a zone is specified, set the availability zone in the
                    // request
                    // Else, proceed with no availability zone which will result
                    // with the cheapest Spot price
                    if (getAvailabilityZones(ec2).contains(zone)) {
                        request.setAvailabilityZone(zone);
                        zoneStr = zone + " availability zone";
                    } else {
                        zoneStr = region + " region";
                    }

                    /*
                     * Iterate through the AWS instance types to see if can find a match for the databound String type.
                     * This is necessary because the AWS API needs the instance type string formatted a particular way
                     * to retrieve prices and the form gives us the strings in a different format. For example "T1Micro"
                     * vs "t1.micro".
                     */
                    InstanceType ec2Type = null;

                    for (InstanceType it : InstanceType.values()) {
                        if (it.name().equals(type)) {
                            ec2Type = it;
                            break;
                        }
                    }

                    /*
                     * If the type string cannot be matched with an instance type, throw a Form error
                     */
                    if (ec2Type == null) {
                        return FormValidation.error("Could not resolve instance type: " + type);
                    }

                    Collection<String> instanceType = new ArrayList<String>();
                    instanceType.add(ec2Type.toString());
                    request.setInstanceTypes(instanceType);
                    request.setStartTime(new Date());

                    // Retrieve the price history request result and store the
                    // current price
                    DescribeSpotPriceHistoryResult result = ec2.describeSpotPriceHistory(request);

                    if (!result.getSpotPriceHistory().isEmpty()) {
                        SpotPrice currentPrice = result.getSpotPriceHistory().get(0);

                        cp = currentPrice.getSpotPrice();
                    }

                } catch (AmazonServiceException e) {
                    return FormValidation.error(e.getMessage());
                }
            }
            /*
             * If we could not return the current price of the instance display an error Else, remove the additional
             * zeros from the current price and return it to the interface in the form of a message
             */
            if (cp.isEmpty()) {
                return FormValidation.error("Could not retrieve current Spot price");
            } else {
                cp = cp.substring(0, cp.length() - 3);

                return FormValidation.ok("The current Spot price for a " + type + " in the " + zoneStr + " is $" + cp);
            }
        }
    }

}
