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
import hudson.Extension;
import hudson.Util;
import hudson.model.Failure;
import hudson.model.ItemGroup;
import hudson.plugins.ec2.util.AmazonEC2Factory;
import hudson.slaves.Cloud;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeRegionsResult;
import com.amazonaws.services.ec2.model.Region;

/**
 * The original implementation of {@link EC2Cloud}.
 *
 * @author Kohsuke Kawaguchi
 */
public class AmazonEC2Cloud extends EC2Cloud {
    private final static Logger LOGGER = Logger.getLogger(AmazonEC2Cloud.class.getName());
    
    /**
     * Represents the region. Can be null for backward compatibility reasons.
     */
    private String region;

    private String altEC2Endpoint;

    public static final String CLOUD_ID_PREFIX = "ec2-";

    private boolean noDelayProvisioning;

    @DataBoundConstructor
    public AmazonEC2Cloud(String cloudName, boolean useInstanceProfileForCredentials, String credentialsId, String region, String privateKey, String sshKeysCredentialsId, String instanceCapStr, List<? extends SlaveTemplate> templates, String roleArn, String roleSessionName) {
        super(createCloudId(cloudName), useInstanceProfileForCredentials, credentialsId, privateKey, sshKeysCredentialsId, instanceCapStr, templates, roleArn, roleSessionName);
        this.region = region;
    }

    @Deprecated
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
            return new URL("https://" + getAwsPartitionHostForService(region, "ec2"));
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
            return new URL("https://" + getAwsPartitionHostForService(getRegion(), "s3") + "/");
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

    public String getAltEC2Endpoint() {
        return altEC2Endpoint;
    }

    @DataBoundSetter
    public void setAltEC2Endpoint(String altEC2Endpoint) {
        this.altEC2Endpoint = altEC2Endpoint;
    }

    @Override
    protected AWSCredentialsProvider createCredentialsProvider() {
        return createCredentialsProvider(isUseInstanceProfileForCredentials(), getCredentialsId(), getRoleArn(), getRoleSessionName(), getRegion());
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

        public FormValidation doCheckAltEC2Endpoint(@QueryParameter String value) {
            if (Util.fixEmpty(value) != null) {
                try {
                    new URL(value);
                } catch (MalformedURLException ignored) {
                    return FormValidation.error(Messages.AmazonEC2Cloud_MalformedUrl());
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
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

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
        //VisibleForTesting
        URL determineEC2EndpointURL(@Nullable String altEC2Endpoint) throws MalformedURLException {
            if (Util.fixEmpty(altEC2Endpoint) == null) {
                return new URL(DEFAULT_EC2_ENDPOINT);
            }
            try {
                return new URL(altEC2Endpoint);    
            } catch (MalformedURLException e) {
                LOGGER.log(Level.WARNING, "The alternate EC2 endpoint is malformed ({0}). Using the default endpoint ({1})", new Object[]{altEC2Endpoint, DEFAULT_EC2_ENDPOINT});
                return new URL(DEFAULT_EC2_ENDPOINT);
            }
        }

        @RequirePOST
        public FormValidation doTestConnection(
                @AncestorInPath ItemGroup context,
                @QueryParameter String region,
                @QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId,
                @QueryParameter String sshKeysCredentialsId,
                @QueryParameter String roleArn,
                @QueryParameter String roleSessionName)

                throws IOException, ServletException {

            if (Util.fixEmpty(region) == null) {
                region = DEFAULT_EC2_HOST;
            }

            return super.doTestConnection(context, getEc2EndpointUrl(region), useInstanceProfileForCredentials, credentialsId, sshKeysCredentialsId, roleArn, roleSessionName, region);
        }
    }
}
