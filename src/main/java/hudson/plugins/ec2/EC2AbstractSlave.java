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
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DeleteTagsRequest;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceBlockDeviceMapping;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.StopInstancesRequest;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

/**
 * Slave running on EC2.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("serial")
public abstract class EC2AbstractSlave extends Slave {
    private static final Logger LOGGER = Logger.getLogger(EC2AbstractSlave.class.getName());

    protected String instanceId;

    /**
     * Comes from {@link SlaveTemplate#initScript}.
     */
    public final String initScript;
    public final String tmpDir;
    public final String remoteAdmin; // e.g. 'ubuntu'

    public final String templateDescription;

    public final String jvmopts; // e.g. -Xmx1g
    public final boolean stopOnTerminate;
    public final String idleTerminationMinutes;
    public final boolean useDedicatedTenancy;
    public boolean isConnected = false;
    public List<EC2Tag> tags;
    public final String cloudName;
    public AMITypeData amiType;
    public int maxTotalUses;
    private String instanceType;

    // Temporary stuff that is obtained live from EC2
    public transient String publicDNS;
    public transient String privateDNS;

    /* The last instance data to be fetched for the slave */
    protected transient Instance lastFetchInstance = null;

    /* The time at which we fetched the last instance data */
    protected transient long lastFetchTime;

    /*
     * The time (in milliseconds) after which we will always re-fetch externally changeable EC2 data when we are asked
     * for it
     */
    protected static final long MIN_FETCH_TIME = Long.getLong("hudson.plugins.ec2.EC2AbstractSlave.MIN_FETCH_TIME",
            TimeUnit.SECONDS.toMillis(20));

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

    private transient long createdTime;

    public static final String TEST_ZONE = "testZone";

    public EC2AbstractSlave(String name, String instanceId, String description, String remoteFS, int numExecutors, Mode mode, String labelString, ComputerLauncher launcher, RetentionStrategy<EC2Computer> retentionStrategy, String initScript, String tmpDir, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, List<EC2Tag> tags, String cloudName, boolean useDedicatedTenancy, int launchTimeout, AMITypeData amiType, ConnectionStrategy connectionStrategy, int maxTotalUses)
            throws FormException, IOException {

        super(name, remoteFS, launcher);
        setNumExecutors(numExecutors);
        setMode(mode);
        setLabelString(labelString);
        setRetentionStrategy(retentionStrategy);
        setNodeProperties(nodeProperties);

        this.instanceId = instanceId;
        this.templateDescription = description;
        this.initScript = initScript;
        this.tmpDir = tmpDir;
        this.remoteAdmin = remoteAdmin;
        this.jvmopts = jvmopts;
        this.stopOnTerminate = stopOnTerminate;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.tags = tags;
        this.usePrivateDnsName = connectionStrategy == ConnectionStrategy.PRIVATE_DNS;
        this.useDedicatedTenancy = useDedicatedTenancy;
        this.cloudName = cloudName;
        this.launchTimeout = launchTimeout;
        this.amiType = amiType;
        this.maxTotalUses = maxTotalUses;
        readResolve();
        fetchLiveInstanceData(true);
    }

    @Deprecated
    public EC2AbstractSlave(String name, String instanceId, String description, String remoteFS, int numExecutors, Mode mode, String labelString, ComputerLauncher launcher, RetentionStrategy<EC2Computer> retentionStrategy, String initScript, String tmpDir, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, List<EC2Tag> tags, String cloudName, boolean usePrivateDnsName, boolean useDedicatedTenancy, int launchTimeout, AMITypeData amiType)
            throws FormException, IOException {
        this(name, instanceId, description, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, initScript, tmpDir, nodeProperties, remoteAdmin, jvmopts, stopOnTerminate, idleTerminationMinutes, tags, cloudName, useDedicatedTenancy, launchTimeout, amiType, ConnectionStrategy.backwardsCompatible(usePrivateDnsName, false, false), -1);
    }

    @Override
    protected Object readResolve() {
        /*
         * If instanceId is null, this object was deserialized from an old version of the plugin, where this field did
         * not exist (prior to version 1.18). In those versions, the node name *was* the instance ID, so we can get it
         * from there.
         */
        if (instanceId == null) {
            instanceId = getNodeName();
        }

        if (amiType == null) {
            amiType = new UnixData(rootCommandPrefix, slaveCommandPrefix, slaveCommandSuffix, Integer.toString(sshPort));
        }

        return this;
    }

    public EC2Cloud getCloud() {
        return (EC2Cloud) Jenkins.get().getCloud(cloudName);
    }

    /**
     * See http://aws.amazon.com/ec2/instance-types/
     */
    /* package */static int toNumExecutors(InstanceType it) {
        switch (it) {
        case T1Micro:
            return 1;
        case M1Small:
            return 1;
        case M1Medium:
            return 2;
        case M3Medium:
            return 2;
        case T3Nano:
            return 2;
        case T3Micro:
            return 2;
        case T3Small:
            return 2;
        case T3Medium:
            return 2;
        case A1Large:
            return 2;
        case T3Large:
            return 3;
        case M1Large:
            return 4;
        case M3Large:
            return 4;
        case M4Large:
            return 4;
        case M5Large:
            return 4;
        case M5aLarge:
            return 4;
        case T3Xlarge:
            return 5;
        case A1Xlarge:
            return 5;
        case C1Medium:
            return 5;
        case M2Xlarge:
            return 6;
        case C3Large:
            return 7;
        case C4Large:
            return 7;
        case C5Large:
            return 7;
        case C5dLarge:
            return 7;
        case M1Xlarge:
            return 8;
        case T32xlarge:
            return 10;
        case A12xlarge:
            return 10;
        case M22xlarge:
            return 13;
        case M3Xlarge:
            return 13;
        case M4Xlarge:
            return 13;
        case M5Xlarge:
            return 13;
        case M5aXlarge:
            return 13;
        case A14xlarge:
            return 14;
        case C3Xlarge:
            return 14;
        case C4Xlarge:
            return 14;
        case C5Xlarge:
            return 14;
        case C5dXlarge:
            return 14;
        case C1Xlarge:
            return 20;
        case M24xlarge:
            return 26;
        case M32xlarge:
            return 26;
        case M42xlarge:
            return 26;
        case M52xlarge:
            return 26;
        case M5a2xlarge:
            return 26;
        case G22xlarge:
            return 26;
        case C32xlarge:
            return 28;
        case C42xlarge:
            return 28;
        case C52xlarge:
            return 28;
        case C5d2xlarge:
            return 28;
        case Cc14xlarge:
            return 33;
        case Cg14xlarge:
            return 33;
        case Hi14xlarge:
            return 35;
        case Hs18xlarge:
            return 35;
        case C34xlarge:
            return 55;
        case C44xlarge:
            return 55;
        case C54xlarge:
            return 55;
        case C5d4xlarge:
            return 55;
        case M44xlarge:
            return 55;
        case M54xlarge:
            return 55;
        case M5a4xlarge:
            return 55;
        case Cc28xlarge:
            return 88;
        case Cr18xlarge:
            return 88;
        case C38xlarge:
            return 108;
        case C48xlarge:
            return 108;
        case C59xlarge:
            return 108;
        case C5d9xlarge:
            return 108;
        case M410xlarge:
            return 120;
        case M512xlarge:
            return 120;
        case M5a12xlarge:
            return 120;
        case M416xlarge:
            return 160;
        case C518xlarge:
            return 216;
        case C5d18xlarge:
            return 216;
        case M524xlarge:
            return 240;
        case M5a24xlarge:
            return 240;
            // We don't have a suggestion, but we don't want to fail completely
            // surely?
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
    public abstract void terminate();

    void stop() {
        try {
            AmazonEC2 ec2 = getCloud().connect();
            StopInstancesRequest request = new StopInstancesRequest(Collections.singletonList(getInstanceId()));
            LOGGER.fine("Sending stop request for " + getInstanceId());
            ec2.stopInstances(request);
            LOGGER.info("EC2 instance stop request sent for " + getInstanceId());
            Computer computer = toComputer();
            if (computer != null) {
                computer.disconnect(null);
            }
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING, "Failed to stop EC2 instance: " + getInstanceId(), e);
        }

    }

    boolean terminateInstance() {
        try {
            AmazonEC2 ec2 = getCloud().connect();
            TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(getInstanceId()));
            LOGGER.fine("Sending terminate request for " + getInstanceId());
            ec2.terminateInstances(request);
            LOGGER.info("EC2 instance terminate request sent for " + getInstanceId());
            return true;
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING, "Failed to terminate EC2 instance: " + getInstanceId(), e);
            return false;
        }
    }

    @Override
    public Node reconfigure(final StaplerRequest req, JSONObject form) throws FormException {
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

    void idleTimeout() {
        LOGGER.info("EC2 instance idle time expired: " + getInstanceId());
        if (!stopOnTerminate) {
            terminate();
        } else {
            stop();
        }
    }

    public long getLaunchTimeoutInMillis() {
        // this should be fine as long as launchTimeout remains an int type
        return launchTimeout * 1000L;
    }

    String getRemoteAdmin() {
        if (remoteAdmin == null || remoteAdmin.length() == 0)
            return amiType.isWindows() ? "Administrator" : "root";
        return remoteAdmin;
    }

    String getRootCommandPrefix() {
        String commandPrefix = amiType.isUnix() ? ((UnixData) amiType).getRootCommandPrefix() : "";
        if (commandPrefix == null || commandPrefix.length() == 0)
            return "";
        return commandPrefix + " ";
    }

    String getSlaveCommandPrefix() {
        String commandPrefix = amiType.isUnix() ? ((UnixData) amiType).getSlaveCommandPrefix() : "";
        if (commandPrefix == null || commandPrefix.length() == 0)
            return "";
        return commandPrefix + " ";
    }

    String getSlaveCommandSuffix() {
        String commandSuffix = amiType.isUnix() ? ((UnixData) amiType).getSlaveCommandSuffix() : "";
        if (commandSuffix == null || commandSuffix.length() == 0)
            return "";
        return " " + commandSuffix;
    }

    String getJvmopts() {
        return Util.fixNull(jvmopts);
    }

    public int getSshPort() {
        String sshPort = amiType.isUnix() ? ((UnixData) amiType).getSshPort() : "22";
        if (sshPort == null || sshPort.length() == 0)
            return 22;

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
     * Called when the slave is connected to Jenkins
     */
    public void onConnected() {
        isConnected = true;
    }

    protected boolean isAlive(boolean force) {
        fetchLiveInstanceData(force);
        if (lastFetchInstance == null)
            return false;
        if (lastFetchInstance.getState().getName().equals(InstanceStateName.Terminated.toString()))
            return false;
        return true;
    }

    /*
     * Much of the EC2 data is beyond our direct control, therefore we need to refresh it from time to time to ensure we
     * reflect the reality of the instances.
     */
    private void fetchLiveInstanceData(boolean force) throws AmazonClientException {
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
            LOGGER.fine("InterruptedException while get " + getInstanceId()
                    + " Exception: " + e);
            return;
        }


        lastFetchTime = now;
        lastFetchInstance = i;
        if (i == null)
            return;

        publicDNS = i.getPublicDnsName();
        privateDNS = i.getPrivateIpAddress();
        createdTime = i.getLaunchTime().getTime();
        instanceType = i.getInstanceType();

        /*
         * Only fetch tags from live instance if tags are set. This check is required to mitigate a race condition
         * when fetchLiveInstanceData() is called before pushLiveInstancedata().
         */
        if(!i.getTags().isEmpty()) {
            tags = new LinkedList<EC2Tag>();
            for (Tag t : i.getTags()) {
                tags.add(new EC2Tag(t.getKey(), t.getValue()));
            }
        }
    }

    /*
     * Clears all existing tag data so that we can force the instance into a known state
     */
    protected void clearLiveInstancedata() throws AmazonClientException {
        Instance inst = null;
        try {
            inst = CloudHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        } catch (InterruptedException e) {
            // We'll just retry next time we test for idleness.
            LOGGER.fine("InterruptedException while get " + getInstanceId()
                    + " Exception: " + e);
            return;
        }


        /* Now that we have our instance, we can clear the tags on it */
        if (!tags.isEmpty()) {
            HashSet<Tag> instTags = new HashSet<Tag>();

            for (EC2Tag t : tags) {
                instTags.add(new Tag(t.getName(), t.getValue()));
            }

            List<String> resources = getResourcesToTag(inst);
            DeleteTagsRequest tagRequest = new DeleteTagsRequest();
            tagRequest.withResources(resources).setTags(instTags);
            getCloud().connect().deleteTags(tagRequest);
        }
    }

    /*
     * Sets tags on an instance and on the volumes attached to it. This will not clear existing tag data, so call
     * clearLiveInstancedata if needed
     */
    protected void pushLiveInstancedata() throws AmazonClientException {
        Instance inst = null;
        try {
            inst = CloudHelper.getInstanceWithRetry(getInstanceId(), getCloud());
        } catch (InterruptedException e) {
            // We'll just retry next time we test for idleness.
            LOGGER.fine("InterruptedException while get " + getInstanceId()
                    + " Exception: " + e);
        }


        /* Now that we have our instance, we can set tags on it */
        if (inst != null && tags != null && !tags.isEmpty()) {
            HashSet<Tag> instTags = new HashSet<Tag>();

            for (EC2Tag t : tags) {
                instTags.add(new Tag(t.getName(), t.getValue()));
            }

            List<String> resources = getResourcesToTag(inst);
            CreateTagsRequest tagRequest = new CreateTagsRequest();
            tagRequest.withResources(resources).setTags(instTags);
            getCloud().connect().createTags(tagRequest);
        }
    }

    /*
     * Get resources to tag, that is the instance itself and the volumes attached to it.
     */
    private List<String> getResourcesToTag(Instance inst) {
        List<String> resources = new ArrayList<>();
        resources.add(inst.getInstanceId());
        for(InstanceBlockDeviceMapping blockDeviceMapping : inst.getBlockDeviceMappings()) {
            resources.add(blockDeviceMapping.getEbs().getVolumeId());
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

    public long getCreatedTime() {
        fetchLiveInstanceData(false);
        return createdTime;
    }

    @Deprecated
    public boolean getUsePrivateDnsName() {
        return usePrivateDnsName;
    }

    public Secret getAdminPassword() {
        return amiType.isWindows() ? ((WindowsData) amiType).getPassword() : Secret.fromString("");
    }

    public boolean isUseHTTPS() {
        return amiType.isWindows() && ((WindowsData) amiType).isUseHTTPS();
    }

    public int getBootDelay() {
        return amiType.isWindows() ? ((WindowsData) amiType).getBootDelayInMillis() : 0;
    }

    public static ListBoxModel fillZoneItems(AWSCredentialsProvider credentialsProvider, String region) {
        ListBoxModel model = new ListBoxModel();
        if (AmazonEC2Cloud.isTestMode()) {
            model.add(TEST_ZONE);
            return model;
        }

        if (!StringUtils.isEmpty(region)) {
            AmazonEC2 client = EC2Cloud.connect(credentialsProvider, AmazonEC2Cloud.getEc2EndpointUrl(region));
            DescribeAvailabilityZonesResult zones = client.describeAvailabilityZones();
            List<AvailabilityZone> zoneList = zones.getAvailabilityZones();
            model.add("<not specified>", "");
            for (AvailabilityZone z : zoneList) {
                model.add(z.getZoneName(), z.getZoneName());
            }
        }
        return model;
    }

    /*
     * Used to determine if the slave is On Demand or Spot
     */
    abstract public String getEc2Type();

    public static abstract class DescriptorImpl extends SlaveDescriptor {

        @Override
        public abstract String getDisplayName();

        @Override
        public boolean isInstantiable() {
            return false;
        }

        public ListBoxModel doFillZoneItems(@QueryParameter boolean useInstanceProfileForCredentials,
                                            @QueryParameter String credentialsId,
                                            @QueryParameter String region,
                                            @QueryParameter String roleArn,
                                            @QueryParameter String roleSessionName) {
            AWSCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
            return fillZoneItems(credentialsProvider, region);
        }

        public List<Descriptor<AMITypeData>> getAMITypeDescriptors() {
            return Jenkins.get().getDescriptorList(AMITypeData.class);
        }
    }

}
