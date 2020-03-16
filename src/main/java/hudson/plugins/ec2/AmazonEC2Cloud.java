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

import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeRegionsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.Region;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StopInstancesRequest;
import com.amazonaws.services.ec2.model.Tag;
import com.google.common.annotations.VisibleForTesting;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Failure;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.plugins.ec2.util.AmazonEC2Factory;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * The original implementation of {@link EC2Cloud}.
 *
 * @author Kohsuke Kawaguchi
 */
public class AmazonEC2Cloud extends EC2Cloud {
    /**
     * Represents the region. Can be null for backward compatibility reasons.
     */
    private String region;

    private String altEC2Endpoint;

    private static final Logger LOGGER = Logger.getLogger(AmazonEC2Cloud.class.getName());

    public static final String CLOUD_ID_PREFIX = "ec2-";

    private static final int MAX_RESULTS = 1000;

    private static final String INSTANCE_NAME_TAG = "Name";

    private static final String TAG_PREFIX = "tag";

    private boolean noDelayProvisioning;

    private boolean startStopNodes;

    private String instanceTagForJenkins;

    private String nodeLabelForEc2;

    private String preventStopAwsTag;

    private String maxIdleMinutes;

    @DataBoundConstructor
    public AmazonEC2Cloud(String cloudName, boolean useInstanceProfileForCredentials, String credentialsId, String region, String privateKey, String instanceCapStr, List<? extends SlaveTemplate> templates, String roleArn, String roleSessionName) {
        super(createCloudId(cloudName), useInstanceProfileForCredentials, credentialsId, privateKey, instanceCapStr, templates, roleArn, roleSessionName);
        this.region = region;
    }

    public String getCloudName() {
        return this.name.substring(CLOUD_ID_PREFIX.length());
    }

    @Override
    public String getDisplayName() {
        return getCloudName();
    }

    private static String createCloudId(String cloudName) {
        return CLOUD_ID_PREFIX + cloudName.trim();
    }

    public String getRegion() {
        if (region == null)
            region = DEFAULT_EC2_HOST; // Backward compatibility
        // Handles pre 1.14 region names that used the old AwsRegion enum, note we don't change
        // the region here to keep the meta-data compatible in the case of a downgrade (is that right?)
        if (region.indexOf('_') > 0)
            return region.replace('_', '-').toLowerCase(Locale.ENGLISH);
        return region;
    }

    public static URL getEc2EndpointUrl(String region) {
        try {
            return new URL("https://ec2." + region + "." + AWS_URL_HOST + "/");
        } catch (MalformedURLException e) {
            throw new Error(e); // Impossible
        }
    }

    @Override
    public URL getEc2EndpointUrl() {
        return getEc2EndpointUrl(getRegion());
    }

    @Override
    public URL getS3EndpointUrl() {
        try {
            return new URL("https://" + getRegion() + ".s3.amazonaws.com/");
        } catch (MalformedURLException e) {
            throw new Error(e); // Impossible
        }
    }

    public boolean isNoDelayProvisioning() {
        return noDelayProvisioning;
    }

    @DataBoundSetter
    public void setNoDelayProvisioning(boolean noDelayProvisioning) {
        this.noDelayProvisioning = noDelayProvisioning;
    }

    @DataBoundSetter
    public void setStartStopNodes(boolean startStopNodes) {
        this.startStopNodes = startStopNodes;
    }

    public boolean isStartStopNodes() {
        return startStopNodes;
    }

    public String getInstanceTagForJenkins() {
        return instanceTagForJenkins;
    }

    @DataBoundSetter
    public void setInstanceTagForJenkins(String instanceTagForJenkins) {
        this.instanceTagForJenkins = instanceTagForJenkins;
    }

    public String getAltEC2Endpoint() {
        return altEC2Endpoint;
    }

    @DataBoundSetter
    public void setAltEC2Endpoint(String altEC2Endpoint) {
        this.altEC2Endpoint = altEC2Endpoint;
    }

    public String getNodeLabelForEc2() {
        return nodeLabelForEc2;
    }

    @DataBoundSetter
    public void setNodeLabelForEc2(String nodeLabelForEc2 ) {
        this.nodeLabelForEc2 = nodeLabelForEc2;
    }

    public String getPreventStopAwsTag() {
       return preventStopAwsTag;
    }

    @DataBoundSetter
    public void setPreventStopAwsTag( String preventStopAwsTag ) {
        this.preventStopAwsTag = preventStopAwsTag;
    }

    public boolean isEc2Node(Node node) {
        //If no label is specified then we check all nodes
        if ( nodeLabelForEc2 == null || nodeLabelForEc2.trim().length() == 0) {
            return true;
        }

        for (LabelAtom label : node.getAssignedLabels()) {
            if (label.getExpression().equalsIgnoreCase( nodeLabelForEc2 )) {
                return true;
            }
        }
        return false;
    }

    public String getMaxIdleMinutes() {
        return maxIdleMinutes;
    }

    @DataBoundSetter
    public void setMaxIdleMinutes(String maxIdleMinutes) {
        this.maxIdleMinutes = maxIdleMinutes;
    }

    public PlannedNode startNode(Node node) {
        Instance nodeInstance = getInstanceByLabel(node.getSelfLabel().getExpression(), InstanceStateName.Stopped);
        if (nodeInstance == null) {
            nodeInstance = getInstanceByNodeName(node.getNodeName(), InstanceStateName.Stopped);
        }

        if (nodeInstance == null) {
            return null;
        }

        final String instanceId = nodeInstance.getInstanceId();

        return new PlannedNode(node.getDisplayName(),
                Computer.threadPoolForRemoting.submit(() -> {
                    try {
                        while (true) {
                            StartInstancesRequest startRequest = new StartInstancesRequest();
                            startRequest.setInstanceIds(Collections.singletonList(instanceId));
                            connect().startInstances(startRequest);

                            Instance instance = CloudHelper.getInstanceWithRetry(instanceId, this);
                            if (instance == null) {
                                LOGGER.log(Level.WARNING, "Can't find instance with instance id `{0}` in cloud {1}. Terminate provisioning ", new Object[] {
                                        instanceId, this.getCloudName() });
                                return null;
                            }

                            InstanceStateName state = InstanceStateName.fromValue(instance.getState().getName());
                            if (state.equals(InstanceStateName.Running)) {
                                long startTime = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - instance.getLaunchTime().getTime());
                                LOGGER.log(Level.INFO, "{0} moved to RUNNING state in {1} seconds and is ready to be connected by Jenkins", new Object[] {
                                        instanceId, startTime });
                                return node;
                            }

                            if (!state.equals(InstanceStateName.Pending)) {
                                LOGGER.log(Level.WARNING, "{0}. Node {1} is neither pending nor running, it's {2}. Terminate provisioning", new Object[] {
                                        instanceId, node.getNodeName(), state });
                                return null;
                            }

                            Thread.sleep(5000);
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Unable to start " + instanceId, e);
                        return null;
                    }
                })
                , node.getNumExecutors());
    }

    public void stopNode(Node node) {
        Instance nodeInstance = getInstanceByLabel(node.getSelfLabel().getExpression(), InstanceStateName.Running);
        if (nodeInstance == null) {
            nodeInstance = getInstanceByNodeName(node.getNodeName(), InstanceStateName.Running);
        }

        if (nodeInstance == null) {
            return;
        }

        final String instanceId = nodeInstance.getInstanceId();

        if (stopAllowed( nodeInstance )) {
            try {
                StopInstancesRequest request = new StopInstancesRequest();
                request.setInstanceIds( Collections.singletonList( instanceId ) );
                connect().stopInstances( request );
                LOGGER.log( Level.INFO, "Stopped instance: {0}", instanceId );
            } catch ( Exception e ) {
                LOGGER.log( Level.INFO, "Unable to stop instance: " + instanceId, e );
            }
        } else {
            LOGGER.log( Level.FINEST, "Not allowed to stop node: {0}", instanceId);
        }
    }

    @Override
    protected AWSCredentialsProvider createCredentialsProvider() {
        return createCredentialsProvider(isUseInstanceProfileForCredentials(), getCredentialsId(), getRoleArn(), getRoleSessionName(), getRegion());
    }

    private Instance getInstanceByLabel(String label, InstanceStateName desiredState) {
        String tag = getInstanceTagForJenkins();
        if (tag == null) {
            return null;
        }
        return getInstance(Collections.singletonList(getTagFilter(tag, label)), desiredState);
    }

    private Instance getInstanceByNodeName(String name, InstanceStateName desiredState) {
        return getInstance(Collections.singletonList(getTagFilter(INSTANCE_NAME_TAG, name)), desiredState);
    }

    private Filter getTagFilter(String name, String value) {
        Filter filter = new Filter();
        filter.setName(TAG_PREFIX + ":" + name.trim());
        filter.setValues(Collections.singletonList(value.trim()));
        LOGGER.log(Level.FINEST,"Created filter to query for instance: {0}", filter);
        return filter;
    }

    private Instance getInstance(List<Filter> filters, InstanceStateName desiredState) {
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        request.setFilters(filters);
        request.setMaxResults(MAX_RESULTS);
        request.setNextToken(null);
        DescribeInstancesResult response = connect().describeInstances( request );

        if (!response.getReservations().isEmpty()) {
            for (Reservation reservation : response.getReservations()) {
                for (Instance instance : reservation.getInstances()) {
                    com.amazonaws.services.ec2.model.InstanceState state = instance.getState();
                    LOGGER.log(Level.FINEST,"Instance {0} state: {1}", new Object[] {instance.getInstanceId(), state.getName()});
                    if (state.getName().equals(desiredState.toString())) {
                        return instance;
                    }
                }
            }
        } else {
            LOGGER.log(Level.FINEST,"No instances found that matched filter criteria");
        }
        return null;
    }

    private boolean stopAllowed(Instance instance) {
        List<Tag> tags = instance.getTags();
        if (tags != null) {
            for ( Tag tag : tags) {
                if (tag.getKey().trim().equals( preventStopAwsTag )) {
                    return false;
                }
            }
        }
        return true;
    }

    @Extension
    public static class DescriptorImpl extends EC2Cloud.DescriptorImpl {

        @Override
        public String getDisplayName() {
            return "Amazon EC2";
        }

        public FormValidation doCheckCloudName(@QueryParameter String value) {
            try {
                Jenkins.checkGoodName(value);
            } catch (Failure e) {
                return FormValidation.error(e.getMessage());
            }

            String cloudId = createCloudId(value);
            int found = 0;
            for (Cloud c : Jenkins.get().clouds) {
                if (c.name.equals(cloudId)) {
                    found++;
                }
            }
            if (found > 1) {
                return FormValidation.error(Messages.AmazonEC2Cloud_NonUniqName());
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillRegionItems(
                @QueryParameter String altEC2Endpoint,
                @QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId)

                throws IOException, ServletException {

            ListBoxModel model = new ListBoxModel();

            try {
                AWSCredentialsProvider credentialsProvider = createCredentialsProvider(useInstanceProfileForCredentials,
                        credentialsId);
                AmazonEC2 client = AmazonEC2Factory.getInstance().connect(credentialsProvider, determineEC2EndpointURL(altEC2Endpoint));
                DescribeRegionsResult regions = client.describeRegions();
                List<Region> regionList = regions.getRegions();
                for (Region r : regionList) {
                    String name = r.getRegionName();
                    model.add(name, name);
                }
            } catch (SdkClientException ex) {
                // Ignore, as this may happen before the credentials are specified
            }
            return model;
        }

        // Will use the alternate EC2 endpoint if provided by the UI (via a @QueryParameter field), or use the default
        // value if not specified.
        @VisibleForTesting
        URL determineEC2EndpointURL(@Nullable String altEC2Endpoint) throws MalformedURLException {
            if (Util.fixEmpty(altEC2Endpoint) == null) {
                return new URL(DEFAULT_EC2_ENDPOINT);
            }

            return new URL(altEC2Endpoint);
        }

        @RequirePOST
        public FormValidation doTestConnection(
                @QueryParameter String region,
                @QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId,
                @QueryParameter String privateKey,
                @QueryParameter String roleArn,
                @QueryParameter String roleSessionName)

                throws IOException, ServletException {

            if (Util.fixEmpty(region) == null) {
                region = DEFAULT_EC2_HOST;
            }

            return super.doTestConnection(getEc2EndpointUrl(region), useInstanceProfileForCredentials, credentialsId, privateKey, roleArn, roleSessionName, region);
        }
    }
}
