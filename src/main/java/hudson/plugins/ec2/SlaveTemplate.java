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

import com.amazonaws.AmazonServiceException;
import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.plugins.ec2.util.DeviceMappingParser;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.slaves.iterators.api.NodeIterator;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;

/**
 * Template of {@link EC2AbstractSlave} to launch.
 *
 * @author Kohsuke Kawaguchi
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {
    public final String ami;
    public final String description;
    public final String zone;
    public final SpotConfiguration spotConfig;
    public final String securityGroups;
    public final String remoteFS;
    public final InstanceType type;
    public final String labels;
    public final Node.Mode mode;
    public final String initScript;
    public final String userData;
    public final String numExecutors;
    public final String remoteAdmin;
    public final String jvmopts;
    public final String subnetId;
    public final String idleTerminationMinutes;
    public final String iamInstanceProfile;
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

    private transient /*almost final*/ Set<LabelAtom> labelSet;
	private transient /*almost final*/ Set<String> securityGroupSet;
	
	/* 
	 * Necessary to handle reading from old configurations. The UnixData object is
	 * created in readResolve()
	 */
	@Deprecated
	public transient String sshPort;
	@Deprecated
	public transient String rootCommandPrefix;

    @DataBoundConstructor
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS, InstanceType type, String labelString, Node.Mode mode, String description, String initScript, String userData, String numExecutors, String remoteAdmin, AMITypeData amiType, String jvmopts, boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, boolean usePrivateDnsName, String instanceCapStr, String iamInstanceProfile, boolean useEphemeralDevices, boolean useDedicatedTenancy, String launchTimeoutStr, boolean associatePublicIp, String customDeviceMapping) {
        this.ami = ami;
        this.zone = zone;
        this.spotConfig = spotConfig;
        this.securityGroups = securityGroups;
        this.remoteFS = remoteFS;
        this.amiType = amiType;
        this.type = type;
        this.labels = Util.fixNull(labelString);
        this.mode = mode;
        this.description = description;
        this.initScript = initScript;
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
        this.useDedicatedTenancy = useDedicatedTenancy;

        if (null == instanceCapStr || instanceCapStr.equals("")) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }

        try {
            this.launchTimeout = Integer.parseInt(launchTimeoutStr);
        } catch (NumberFormatException nfe ) {
            this.launchTimeout = Integer.MAX_VALUE;
        }

        this.iamInstanceProfile = iamInstanceProfile;
        this.useEphemeralDevices = useEphemeralDevices;
        this.customDeviceMapping = customDeviceMapping;

        readResolve(); // initialize
    }

    /**
     * Backward compatible constructor for reloading previous version data
     */
    public SlaveTemplate(String ami, String zone, SpotConfiguration spotConfig, String securityGroups, String remoteFS, String sshPort, InstanceType type, String labelString, Node.Mode mode, String description, String initScript, String userData, String numExecutors, String remoteAdmin, String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String subnetId, List<EC2Tag> tags, String idleTerminationMinutes, boolean usePrivateDnsName, String instanceCapStr, String iamInstanceProfile, boolean useEphemeralDevices, String launchTimeoutStr)
    {
    	this(ami, zone, spotConfig, securityGroups, remoteFS, type, labelString, mode, description, initScript, userData, numExecutors, remoteAdmin, new UnixData(rootCommandPrefix, sshPort), jvmopts, stopOnTerminate, subnetId, tags, idleTerminationMinutes, usePrivateDnsName, instanceCapStr, iamInstanceProfile, useEphemeralDevices, false, launchTimeoutStr, false, null); 
    }
    
    public EC2Cloud getParent() {
        return parent;
    }

    public String getBidType(){
        if(spotConfig == null)
            return null;
        return spotConfig.spotInstanceBidType;
    }

    public String getLabelString() {
        return labels;
    }

    public Node.Mode getMode() {
        return mode;
    }

    public String getDisplayName() {
        return description+" ("+ami+")";
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
        		sshPort = ((UnixData)amiType).getSshPort();
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
        return amiType.isUnix() ? ((UnixData)amiType).getRootCommandPrefix() : "";
    }

    public String getSubnetId() {
        return subnetId;
    }

    public boolean getAssociatePublicIp() {
        return associatePublicIp;
    }

    public List<EC2Tag> getTags() {
        if (null == tags) return null;
        return Collections.unmodifiableList(tags);
    }

    public String getidleTerminationMinutes() {
        return idleTerminationMinutes;
    }

    public boolean getUseDedicatedTenancy() {
        return useDedicatedTenancy;
    }

    public Set<LabelAtom> getLabelSet(){
        return labelSet;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public String getInstanceCapStr() {
        if (instanceCap==Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(instanceCap);
        }
    }

    public String getSpotMaxBidPrice(){
        if (spotConfig == null)
            return null;
        return SpotConfiguration.normalizeBid(spotConfig.spotMaxBidPrice);
    }

    public String getIamInstanceProfile() {
        return iamInstanceProfile;
    }

    /**
     * Provisions a new EC2 slave or starts a previously stopped on-demand instance.
     *
     * @return always non-null. This needs to be then added to {@link Hudson#addNode(Node)}.
     */
    public EC2AbstractSlave provision(TaskListener listener) throws AmazonClientException, IOException {
        if (this.spotConfig != null){
            return provisionSpot(listener);
        }
        return provisionOndemand(listener);
    }

    /**
     * Provisions an On-demand EC2 slave by launching a new instance or 
     * starting a previously-stopped instance.
     */
    private EC2AbstractSlave provisionOndemand(TaskListener listener) throws AmazonClientException, IOException {
        PrintStream logger = listener.getLogger();
        AmazonEC2 ec2 = getParent().connect();

        try {
	        String msg = "Launching " + ami + " for template " + description;
            logger.println(msg);
            LOGGER.info(msg);

            KeyPair keyPair = getKeyPair(ec2);

            RunInstancesRequest riRequest = new RunInstancesRequest(ami, 1, 1);
            InstanceNetworkInterfaceSpecification net = new InstanceNetworkInterfaceSpecification();

            if (useEphemeralDevices) {
                setupEphemeralDeviceMapping(riRequest);
            }
            else {
                setupCustomDeviceMapping(riRequest);
            }

            List<Filter> diFilters = new ArrayList<Filter>();
            diFilters.add(new Filter("image-id").withValues(ami));

            if (StringUtils.isNotBlank(getZone())) {
                Placement placement = new Placement(getZone());
                if (getUseDedicatedTenancy()) {
                    placement.setTenancy("dedicated");
                }
                riRequest.setPlacement(placement);
                diFilters.add(new Filter("availability-zone").withValues(getZone()));
            }

            if (StringUtils.isNotBlank(getSubnetId())) {
                if (getAssociatePublicIp()) {
                    net.setSubnetId(getSubnetId());
                }else{
                    riRequest.setSubnetId(getSubnetId());
                }

                diFilters.add(new Filter("subnet-id").withValues(getSubnetId()));

                /* If we have a subnet ID then we can only use VPC security groups */
                if (!securityGroupSet.isEmpty()) {
                    List<String> group_ids = getEc2SecurityGroups(ec2);

                    if (!group_ids.isEmpty()) {
                        if (getAssociatePublicIp()) {
                            net.setGroups(group_ids);
                        }else{
                            riRequest.setSecurityGroupIds(group_ids);
                        }

                        diFilters.add(new Filter("instance.group-id").withValues(group_ids));
                    }
                }
            } else {
               /* No subnet: we can use standard security groups by name */
                riRequest.setSecurityGroups(securityGroupSet);
                if (securityGroupSet.size() > 0)
                    diFilters.add(new Filter("instance.group-name").withValues(securityGroupSet));
            }

            String userDataString = Base64.encodeBase64String(userData.getBytes());
            riRequest.setUserData(userDataString);
            riRequest.setKeyName(keyPair.getKeyName());
            diFilters.add(new Filter("key-name").withValues(keyPair.getKeyName()));
            riRequest.setInstanceType(type.toString());
            diFilters.add(new Filter("instance-type").withValues(type.toString()));

            if (getAssociatePublicIp()) {
                net.setAssociatePublicIpAddress(true);
                net.setDeviceIndex(0);
                riRequest.withNetworkInterfaces(net);
            }

            boolean hasCustomTypeTag = false;
            HashSet<Tag> inst_tags = null;
            if (tags != null && !tags.isEmpty()) {
                inst_tags = new HashSet<Tag>();
                for(EC2Tag t : tags) {
                    inst_tags.add(new Tag(t.getName(), t.getValue()));
                    diFilters.add(new Filter("tag:"+t.getName()).withValues(t.getValue()));
                    if (StringUtils.equals(t.getName(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)) {
                    	hasCustomTypeTag = true;
                    }
                }
            }
            if (!hasCustomTypeTag) {
                if (inst_tags == null){
                    inst_tags = new HashSet<Tag>();
                }
            	inst_tags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "demand"));
            }

            DescribeInstancesRequest diRequest = new DescribeInstancesRequest();
            diFilters.add(new Filter("instance-state-name").withValues(InstanceStateName.Stopped.toString(),
                    InstanceStateName.Stopping.toString()));
            diRequest.setFilters(diFilters);

            msg = "Looking for existing instances with describe-instance: "+diRequest;
            logger.println(msg);
            LOGGER.fine(msg);

            DescribeInstancesResult diResult = ec2.describeInstances(diRequest);

            Instance existingInstance = null;
            if (StringUtils.isNotBlank(getIamInstanceProfile())) {
                riRequest.setIamInstanceProfile(new IamInstanceProfileSpecification().withArn(getIamInstanceProfile()));
                // cannot filter on IAM Instance Profile, so search in result
                reservationLoop:
                for (Reservation reservation : diResult.getReservations()) {
                    for (Instance instance : reservation.getInstances()) {
                        if (instance.getIamInstanceProfile() != null && instance.getIamInstanceProfile().getArn().equals(getIamInstanceProfile())) {
                            existingInstance = instance;
                            break reservationLoop;
                        }
                    }
                }
            } else if (diResult.getReservations().size() > 0) {
                existingInstance = diResult.getReservations().get(0).getInstances().get(0);
            }

            if (existingInstance == null) {
                // Have to create a new instance
                Instance inst = ec2.runInstances(riRequest).getReservation().getInstances().get(0);

                /* Now that we have our instance, we can set tags on it */
                if (inst_tags != null) {
                    updateRemoteTags(ec2, inst_tags, inst.getInstanceId());

                    // That was a remote request - we should also update our local instance data.
                    inst.setTags(inst_tags);
                }
                msg = "No existing instance found - created: "+inst;
                logger.println(msg);
                LOGGER.info(msg);
                return newOndemandSlave(inst);
            }

            msg = "Found existing stopped instance: "+existingInstance;
            logger.println(msg);
            LOGGER.info(msg);

            List<String> instances = new ArrayList<String>();
            instances.add(existingInstance.getInstanceId());
            StartInstancesRequest siRequest = new StartInstancesRequest(instances);
            StartInstancesResult siResult = ec2.startInstances(siRequest);

            msg = "Starting existing instance: "+existingInstance+ " result:"+siResult;
            logger.println(msg);
            LOGGER.fine(msg);

            for (EC2AbstractSlave ec2Node: NodeIterator.nodes(EC2AbstractSlave.class)){
                if (ec2Node.getInstanceId().equals(existingInstance.getInstanceId())) {
                    msg = "Found existing corresponding Jenkins slave: "+ec2Node;
                    logger.println(msg);
                    LOGGER.finer(msg);
                    return ec2Node;
                }
            }

            // Existing slave not found
            msg = "Creating new Jenkins slave for existing instance: "+existingInstance;
            logger.println(msg);
            LOGGER.info(msg);
            return newOndemandSlave(existingInstance);

        } catch (FormException e) {
            throw new AssertionError(); // we should have discovered all configuration issues upfront
        }
    }

    private void setupEphemeralDeviceMapping(RunInstancesRequest riRequest) {

        final List<BlockDeviceMapping> oldDeviceMapping = getAmiBlockDeviceMappings();

        final Set<String> occupiedDevices = new HashSet<String>();
        for (final BlockDeviceMapping mapping: oldDeviceMapping ) {

            occupiedDevices.add(mapping.getDeviceName());
        }

        final List<String> available = new ArrayList<String>(Arrays.asList(
                "ephemeral0", "ephemeral1", "ephemeral2", "ephemeral3"
        ));

        final List<BlockDeviceMapping> newDeviceMapping = new ArrayList<BlockDeviceMapping>(4);
        for (char suffix = 'b'; suffix <= 'z' && !available.isEmpty(); suffix++) {

            final String deviceName = String.format("/dev/xvd%s", suffix);

            if (occupiedDevices.contains(deviceName)) continue;

            final BlockDeviceMapping newMapping = new BlockDeviceMapping()
                    .withDeviceName(deviceName)
                    .withVirtualName(available.get(0))
            ;

            newDeviceMapping.add(newMapping);
            available.remove(0);
        }

        riRequest.withBlockDeviceMappings(newDeviceMapping);
    }

    private List<BlockDeviceMapping> getAmiBlockDeviceMappings() {

        /*
         * AmazonEC2#describeImageAttribute does not work due to a bug
         * https://forums.aws.amazon.com/message.jspa?messageID=231972
         */
        for (final Image image: getParent().connect().describeImages().getImages()) {

            if (ami.equals(image.getImageId())) {

                return image.getBlockDeviceMappings();
            }
        }

        throw new AmazonClientException("Unable to get AMI device mapping for " + ami);
    }

    private void setupCustomDeviceMapping(RunInstancesRequest riRequest) {
        if (StringUtils.isNotBlank(customDeviceMapping)) {
            riRequest.setBlockDeviceMappings(DeviceMappingParser.parse(customDeviceMapping));
        }
    }

    /**
     * Provision a new slave for an EC2 spot instance to call back to Jenkins
     */
    private EC2AbstractSlave provisionSpot(TaskListener listener) throws AmazonClientException, IOException {
        PrintStream logger = listener.getLogger();
        AmazonEC2 ec2 = getParent().connect();

        try{
            logger.println("Launching " + ami + " for template " + description);
            KeyPair keyPair = getKeyPair(ec2);

            RequestSpotInstancesRequest spotRequest = new RequestSpotInstancesRequest();

            // Validate spot bid before making the request
            if (getSpotMaxBidPrice() == null){
                // throw new FormException("Invalid Spot price specified: " + getSpotMaxBidPrice(), "spotMaxBidPrice");
                throw new AmazonClientException("Invalid Spot price specified: " + getSpotMaxBidPrice());
            }

            spotRequest.setSpotPrice(getSpotMaxBidPrice());
            spotRequest.setInstanceCount(Integer.valueOf(1));
            spotRequest.setType(getBidType());

            LaunchSpecification launchSpecification = new LaunchSpecification();
            InstanceNetworkInterfaceSpecification net = new InstanceNetworkInterfaceSpecification();

            launchSpecification.setImageId(ami);
            launchSpecification.setInstanceType(type);

            if (StringUtils.isNotBlank(getZone())) {
                SpotPlacement placement = new SpotPlacement(getZone());
                launchSpecification.setPlacement(placement);
            }

            if (StringUtils.isNotBlank(getSubnetId())) {
                if (getAssociatePublicIp()) {
                    net.setSubnetId(getSubnetId());
                }else{
                    launchSpecification.setSubnetId(getSubnetId());
                }

                /* If we have a subnet ID then we can only use VPC security groups */
                if (!securityGroupSet.isEmpty()) {
                    List<String> group_ids = getEc2SecurityGroups(ec2);
                    if (!group_ids.isEmpty()){
                        if (getAssociatePublicIp()) {
                            net.setGroups(group_ids);
                        }else{
                            ArrayList<GroupIdentifier> groups = new ArrayList<GroupIdentifier>();

                            for (String group_id : group_ids) {
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
                if (securityGroupSet.size() > 0)
                    launchSpecification.setSecurityGroups(securityGroupSet);
            }

            // The slave must know the Jenkins server to register with as well
            // as the name of the node in Jenkins it should register as. The only
            // way to give information to the Spot slaves is through the ec2 user data
            String jenkinsUrl = Hudson.getInstance().getRootUrl();
            // We must provide a unique node name for the slave to connect to Jenkins.
            // We don't have the EC2 generated instance ID, or the Spot request ID
            // until after the instance is requested, which is then too late to set the
            // user-data for the request. Instead we generate a unique name from UUID
            // so that the slave has a unique name within Jenkins to register to.
            String slaveName = UUID.randomUUID().toString();
            String newUserData = "JENKINS_URL=" + jenkinsUrl +
                    "&SLAVE_NAME=" + slaveName +
                    "&USER_DATA=" + Base64.encodeBase64String(userData.getBytes());

            String userDataString = Base64.encodeBase64String(newUserData.getBytes());
            launchSpecification.setUserData(userDataString);
            launchSpecification.setKeyName(keyPair.getKeyName());
            launchSpecification.setInstanceType(type.toString());

             if (getAssociatePublicIp()) {
                net.setAssociatePublicIpAddress(true);
                net.setDeviceIndex(0);
                launchSpecification.withNetworkInterfaces(net);
            }

            boolean hasCustomTypeTag = false;
            HashSet<Tag> inst_tags = null;
            if (tags != null && !tags.isEmpty()) {
                inst_tags = new HashSet<Tag>();
                for(EC2Tag t : tags) {
                    inst_tags.add(new Tag(t.getName(), t.getValue()));
                    if (StringUtils.equals(t.getName(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)) {
                    	hasCustomTypeTag = true;
                    }
                }
            }
            if (!hasCustomTypeTag) {
            	inst_tags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "spot"));
            }

            if (StringUtils.isNotBlank(getIamInstanceProfile())) {
                launchSpecification.setIamInstanceProfile(new IamInstanceProfileSpecification().withArn(getIamInstanceProfile()));
            }

            spotRequest.setLaunchSpecification(launchSpecification);

            // Make the request for a new Spot instance
            RequestSpotInstancesResult reqResult = ec2.requestSpotInstances(spotRequest);

            List<SpotInstanceRequest> reqInstances = reqResult.getSpotInstanceRequests();
            if (reqInstances.size() <= 0){
                throw new AmazonClientException("No spot instances found");
            }

            SpotInstanceRequest spotInstReq = reqInstances.get(0);
            if (spotInstReq == null){
                throw new AmazonClientException("Spot instance request is null");
            }

            /* Now that we have our Spot request, we can set tags on it */
            if (inst_tags != null) {
                for (int i = 0; i < 5; i++) {
                    try {
                        updateRemoteTags(ec2, inst_tags, spotInstReq.getSpotInstanceRequestId());
                        break;
                    } catch (AmazonServiceException e) {
                        if (e.getErrorCode().equals("InvalidSpotInstanceRequestID.NotFound")) {
                            Thread.sleep(5000);
                            continue;
                        }
                        throw e;
                    }
                }

                // That was a remote request - we should also update our local instance data.
                spotInstReq.setTags(inst_tags);
            }

            logger.println("Spot instance id in provision: " + spotInstReq.getSpotInstanceRequestId());

            return newSpotSlave(spotInstReq, slaveName);

        }  catch (FormException e) {
            throw new AssertionError(); // we should have discovered all configuration issues upfront
        }  catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    protected EC2OndemandSlave newOndemandSlave(Instance inst) throws FormException, IOException {
        return new EC2OndemandSlave(inst.getInstanceId(), description, remoteFS, getNumExecutors(), labels, mode, initScript, remoteAdmin, jvmopts, stopOnTerminate, idleTerminationMinutes, inst.getPublicDnsName(), inst.getPrivateDnsName(), EC2Tag.fromAmazonTags(inst.getTags()), parent.name, usePrivateDnsName, useDedicatedTenancy, getLaunchTimeout(), amiType);
    }

    protected EC2SpotSlave newSpotSlave(SpotInstanceRequest sir, String name) throws FormException, IOException {
        return new EC2SpotSlave(name, sir.getSpotInstanceRequestId(), description, remoteFS, getNumExecutors(), mode, initScript, labels, remoteAdmin, jvmopts, idleTerminationMinutes, EC2Tag.fromAmazonTags(sir.getTags()), parent.name, usePrivateDnsName, getLaunchTimeout(), amiType);
    }

    /**
     * Get a KeyPair from the configured information for the slave template
     */
    private KeyPair getKeyPair(AmazonEC2 ec2) throws IOException, AmazonClientException{
        KeyPair keyPair = parent.getPrivateKey().find(ec2);
        if(keyPair==null) {
            throw new AmazonClientException("No matching keypair found on EC2. Is the EC2 private key a valid one?");
        }
        return keyPair;
    }

    /**
     * Update the tags stored in EC2 with the specified information
     */
    private void updateRemoteTags(AmazonEC2 ec2, Collection<Tag> inst_tags, String... params) {
        CreateTagsRequest tag_request = new CreateTagsRequest();
        tag_request.withResources(params).setTags(inst_tags);
        ec2.createTags(tag_request);
    }

    /**
     * Get a list of security group ids for the slave
     */
    private List<String> getEc2SecurityGroups(AmazonEC2 ec2) throws AmazonClientException{
        List<String> group_ids = new ArrayList<String>();

        DescribeSecurityGroupsResult group_result = getSecurityGroupsBy("group-name", securityGroupSet, ec2);
        if (group_result.getSecurityGroups().size() == 0) {
            group_result = getSecurityGroupsBy("group-id", securityGroupSet, ec2);
        }

        for (SecurityGroup group : group_result.getSecurityGroups()) {
            if (group.getVpcId() != null && !group.getVpcId().isEmpty()) {
                List<Filter> filters = new ArrayList<Filter>();
                filters.add(new Filter("vpc-id").withValues(group.getVpcId()));
                filters.add(new Filter("state").withValues("available"));
                filters.add(new Filter("subnet-id").withValues(getSubnetId()));

                DescribeSubnetsRequest subnet_req = new DescribeSubnetsRequest();
                subnet_req.withFilters(filters);
                DescribeSubnetsResult subnet_result = ec2.describeSubnets(subnet_req);

                List<Subnet> subnets = subnet_result.getSubnets();
                if(subnets != null && !subnets.isEmpty()) {
                    group_ids.add(group.getGroupId());
                }
            }
        }

        if (securityGroupSet.size() != group_ids.size()) {
            throw new AmazonClientException( "Security groups must all be VPC security groups to work in a VPC context" );
        }

        return group_ids;
    }

    private DescribeSecurityGroupsResult getSecurityGroupsBy(String filterName, Set<String> filterValues, AmazonEC2 ec2) {
        DescribeSecurityGroupsRequest group_req = new DescribeSecurityGroupsRequest();
        group_req.withFilters(new Filter(filterName).withValues(filterValues));
        return ec2.describeSecurityGroups(group_req);
    }

    /**
     * Provisions a new EC2 slave based on the currently running instance on EC2,
     * instead of starting a new one.
     */
    public EC2AbstractSlave attach(String instanceId, TaskListener listener) throws AmazonClientException, IOException {
        PrintStream logger = listener.getLogger();
        AmazonEC2 ec2 = getParent().connect();

        try {
            logger.println("Attaching to "+instanceId);
            DescribeInstancesRequest request = new DescribeInstancesRequest();
            request.setInstanceIds(Collections.singletonList(instanceId));
            Instance inst = ec2.describeInstances(request).getReservations().get(0).getInstances().get(0);
            return newOndemandSlave(inst);
        } catch (FormException e) {
            throw new AssertionError(); // we should have discovered all configuration issues upfront
        }
    }

    /**
     * Initializes data structure that we don't persist.
     */
    protected Object readResolve() {
        labelSet = Label.parse(labels);
        securityGroupSet = parseSecurityGroups();

        /**
         * In releases of this plugin prior to 1.18, template-specific instance caps could be configured
         * but were not enforced. As a result, it was possible to have the instance cap for a template
         * be configured to 0 (zero) with no ill effects. Starting with version 1.18, template-specific
         * instance caps are enforced, so if a configuration has a cap of zero for a template, no instances
         * will be launched from that template. Since there is no practical value of intentionally setting
         * the cap to zero, this block will override such a setting to a value that means 'no cap'.
         */
        if (instanceCap == 0) {
            instanceCap = Integer.MAX_VALUE;
        }

        if (amiType == null) {
        	amiType = new UnixData(rootCommandPrefix, sshPort);
        }
        return this;
    }

    public Descriptor<SlaveTemplate> getDescriptor() {
        return Hudson.getInstance().getDescriptor(getClass());
    }

    public int getLaunchTimeout() {
        return launchTimeout <= 0 ? Integer.MAX_VALUE : launchTimeout;
    }

    public String getLaunchTimeoutStr() {
        if (launchTimeout==Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(launchTimeout);
        }
    }

    public boolean isWindowsSlave()
    {
      return amiType.isWindows();
    }

    public boolean isUnixSlave()
    {
      return amiType.isUnix();
    }

    public String getAdminPassword()
    {
      return amiType.isWindows() ? ((WindowsData)amiType).getPassword() : "";
    }
    
    private boolean isUseHTTPS() {
        return amiType.isWindows() ? ((WindowsData)amiType).isUseHTTPS() : false;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {
        @Override
        public String getDisplayName() {
            return null;
        }

        public List<Descriptor<AMITypeData>> getAMITypeDescriptors()
        {
            return Hudson.getInstance().<AMITypeData,Descriptor<AMITypeData>>getDescriptorList(AMITypeData.class);
        }
        
        /**
         * Since this shares much of the configuration with {@link EC2Computer}, check its help page, too.
         */
        @Override
        public String getHelpFile(String fieldName) {
            String p = super.getHelpFile(fieldName);
            if (p==null)
                p = Hudson.getInstance().getDescriptor(EC2OndemandSlave.class).getHelpFile(fieldName);
            if (p==null)
                p = Hudson.getInstance().getDescriptor(EC2SpotSlave.class).getHelpFile(fieldName);
            return p;
        }

        /***
         * Check that the AMI requested is available in the cloud and can be used.
         */
        public FormValidation doValidateAmi(
                @QueryParameter String accessId, @QueryParameter String secretKey,
                @QueryParameter String ec2endpoint,  @QueryParameter String region,
                final @QueryParameter String ami) throws IOException, ServletException {
            AmazonEC2 ec2;
            if (region != null) {
                ec2 = EC2Cloud.connect(accessId, secretKey, AmazonEC2Cloud.getEc2EndpointUrl(region));
            } else {
                ec2 = EC2Cloud.connect(accessId, secretKey, new URL(ec2endpoint));
            }
            if(ec2!=null) {
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
                    if(img==null || img.isEmpty()) {
                        // de-registered AMI causes an empty list to be returned. so be defensive
                        // against other possibilities
                        return FormValidation.error("No such AMI, or not usable with this accessId: "+ami);
                    }
                    String ownerAlias = img.get(0).getImageOwnerAlias();
                    return FormValidation.ok(img.get(0).getImageLocation() +
                            (ownerAlias != null ? " by " + ownerAlias : ""));
                } catch (AmazonClientException e) {
                        return FormValidation.error(e.getMessage());
                }
            } else
                return FormValidation.ok();   // can't test
        }

        public FormValidation doCheckLabelString(@QueryParameter String value, @QueryParameter Node.Mode mode) {
            if (mode == Node.Mode.EXCLUSIVE && (value == null || value.trim() == "")) {
                return FormValidation.warning("You may want to assign labels to this node;" +
                        " it's marked to only run jobs that are exclusively tied to itself or a label.");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckIdleTerminationMinutes(@QueryParameter String value) {
            if (value == null || value.trim() == "") return FormValidation.ok();
            try {
                int val = Integer.parseInt(value);
                if (val >= -59) return FormValidation.ok();
            }
            catch ( NumberFormatException nfe ) {}
            return FormValidation.error("Idle Termination time must be a greater than -59 (or null)");
        }

        public FormValidation doCheckInstanceCapStr(@QueryParameter String value) {
            if (value == null || value.trim() == "") return FormValidation.ok();
            try {
                int val = Integer.parseInt(value);
                if (val > 0) return FormValidation.ok();
            } catch ( NumberFormatException nfe ) {}
            return FormValidation.error("InstanceCap must be a non-negative integer (or null)");
        }

        public FormValidation doCheckLaunchTimeoutStr(@QueryParameter String value) {
            if (value == null || value.trim() == "") return FormValidation.ok();
            try {
                int val = Integer.parseInt(value);
                if (val >= 0) return FormValidation.ok();
            } catch ( NumberFormatException nfe ) {}
            return FormValidation.error("Launch Timeout must be a non-negative integer (or null)");
        }

        public ListBoxModel doFillZoneItems( @QueryParameter String accessId,
                                             @QueryParameter String secretKey,
                                             @QueryParameter String region)
                                             throws IOException, ServletException
        {
            return EC2AbstractSlave.fillZoneItems(accessId, secretKey, region);
        }

        /* Validate the Spot Max Bid Price to ensure that it is a floating point number >= .001 */
        public FormValidation doCheckSpotMaxBidPrice( @QueryParameter String spotMaxBidPrice ) {
            if(SpotConfiguration.normalizeBid(spotMaxBidPrice) != null){
                return FormValidation.ok();
            }
            return FormValidation.error("Not a correct bid price");
        }

        // Retrieve the availability zones for the region
        private ArrayList<String> getAvailabilityZones(AmazonEC2 ec2)  {
            ArrayList<String> availabilityZones = new ArrayList<String>();

            DescribeAvailabilityZonesResult zones = ec2.describeAvailabilityZones();
            List<AvailabilityZone> zoneList = zones.getAvailabilityZones();

            for (AvailabilityZone z : zoneList) {
                availabilityZones.add(z.getZoneName());
            }

            return availabilityZones;
        }

        /**
        * Populates the Bid Type Drop down on the slave template config.
        * @return
        */
        public ListBoxModel doFillBidTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add(SpotInstanceType.OneTime.toString());
            items.add(SpotInstanceType.Persistent.toString());
            return items;
        }

        /* Check the current Spot price of the selected instance type for the selected region */
        public FormValidation doCurrentSpotPrice( @QueryParameter String accessId, @QueryParameter String secretKey,
                @QueryParameter String region, @QueryParameter String type,
                @QueryParameter String zone ) throws IOException, ServletException {

            String cp = "";
            String zoneStr = "";

            // Connect to the EC2 cloud with the access id, secret key, and region queried from the created cloud
            AmazonEC2 ec2 = EC2Cloud.connect(accessId, secretKey, AmazonEC2Cloud.getEc2EndpointUrl(region));

            if(ec2!=null) {

                try {
                    // Build a new price history request with the currently selected type
                    DescribeSpotPriceHistoryRequest request = new DescribeSpotPriceHistoryRequest();
                    // If a zone is specified, set the availability zone in the request
                    // Else, proceed with no availability zone which will result with the cheapest Spot price
                    if(getAvailabilityZones(ec2).contains(zone)){
                        request.setAvailabilityZone(zone);
                        zoneStr = zone + " availability zone";
                    } else {
                        zoneStr = region + " region";
                    }

                    /*
                     * Iterate through the AWS instance types to see if can find a match for the databound
                     * String type. This is necessary because the AWS API needs the instance type
                     * string formatted a particular way to retrieve prices and the form gives us the strings
                     * in a different format. For example "T1Micro" vs "t1.micro".
                     */
                    InstanceType ec2Type = null;

                    for(InstanceType it : InstanceType.values()){
                        if (it.name().toString().equals(type)){
                            ec2Type = it;
                            break;
                        }
                    }

                    /*
                     * If the type string cannot be matched with an instance type,
                     * throw a Form error
                     */
                    if(ec2Type == null){
                        return FormValidation.error("Could not resolve instance type: " + type);
                    }

                    Collection<String> instanceType = new ArrayList<String>();
                    instanceType.add(ec2Type.toString());
                    request.setInstanceTypes(instanceType);
                    request.setStartTime(new Date());

                    // Retrieve the price history request result and store the current price
                    DescribeSpotPriceHistoryResult result = ec2.describeSpotPriceHistory(request);

                    if(!result.getSpotPriceHistory().isEmpty()){
                        SpotPrice currentPrice = result.getSpotPriceHistory().get(0);

                        cp = currentPrice.getSpotPrice();
                    }

                } catch (AmazonClientException e) {
                    return FormValidation.error(e.getMessage());
                }
            }
            /*
             * If we could not return the current price of the instance display an error
             * Else, remove the additional zeros from the current price and return it to the interface
             * in the form of a message
             */
            if(cp.isEmpty()){
                return FormValidation.error("Could not retrieve current Spot price");
            } else {
                cp = cp.substring(0, cp.length() - 3);

                return FormValidation.ok("The current Spot price for a " + type +
                        " in the " + zoneStr + " is $" + cp );
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(SlaveTemplate.class.getName());
}

