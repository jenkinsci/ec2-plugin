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

import hudson.Extension;
import hudson.Util;
import hudson.model.Failure;
import hudson.slaves.Cloud;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerResponse;

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
    /**
     * Represents the region. Can be null for backward compatibility reasons.
     */
    private String region;

    private static final Map<String, String> S3_ENDPOINTS;
    static {
        Map<String, String> s3 = new HashMap<String, String>();

        // http://docs.aws.amazon.com/general/latest/gr/rande.html#s3_region

        s3.put("us-east-1",                     "s3.amazonaws.com");
        s3.put("us-west-1",           "s3-us-west-1.amazonaws.com");
        s3.put("us-west-2",           "s3-us-west-2.amazonaws.com");
        s3.put("eu-west-1",           "s3-eu-west-1.amazonaws.com");
        s3.put("eu-central-1",     "s3.eu-central-1.amazonaws.com");
        s3.put("ap-southeast-1", "s3-ap-southeast-1.amazonaws.com");
        s3.put("ap-southeast-2", "s3-ap-southeast-2.amazonaws.com");
        s3.put("ap-northeast-1", "s3-ap-northeast-1.amazonaws.com");
        s3.put("sa-east-1",           "s3-sa-east-1.amazonaws.com");

        S3_ENDPOINTS = Collections.unmodifiableMap(s3);
    }

    public static final String CLOUD_ID_PREFIX = "ec2-";

    // Used when running unit tests
    public static boolean testMode;

    @DataBoundConstructor
    public AmazonEC2Cloud(String cloudName, boolean useInstanceProfileForCredentials, String accessId, String secretKey, String region, String privateKey, String instanceCapStr, List<? extends SlaveTemplate> templates) {
        super(createCloudId(cloudName), useInstanceProfileForCredentials, accessId, secretKey, privateKey, instanceCapStr, templates);
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
            return new URL("https://" + region + "." + EC2_URL_HOST + "/");
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
            return new URL("https://" + S3_ENDPOINTS.get(getRegion()) + "/");
        } catch (MalformedURLException e) {
            throw new Error(e); // Impossible
        }
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
            for (Cloud c : Jenkins.getInstance().clouds) {
                if (c.name.equals(cloudId)) {
                    found++;
                }
            }
            if (found > 1) {
                return FormValidation.error(Messages.AmazonEC2Cloud_NonUniqName());
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillRegionItems(@QueryParameter boolean useInstanceProfileForCredentials, @QueryParameter String accessId, @QueryParameter String secretKey, @QueryParameter String region)
                throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            if (testMode) {
                model.add(DEFAULT_EC2_HOST);
                return model;
            }

            if (useInstanceProfileForCredentials || (!StringUtils.isEmpty(accessId) && !StringUtils.isEmpty(secretKey))) {
                AWSCredentialsProvider credentialsProvider = createCredentialsProvider(useInstanceProfileForCredentials, accessId, secretKey);
                AmazonEC2 client = connect(credentialsProvider, new URL("http://ec2.amazonaws.com"));
                DescribeRegionsResult regions = client.describeRegions();
                List<Region> regionList = regions.getRegions();
                for (Region r : regionList) {
                    String name = r.getRegionName();
                    model.add(name, name);
                }
            }
            return model;
        }

        public FormValidation doTestConnection(@QueryParameter String region, @QueryParameter boolean useInstanceProfileForCredentials, @QueryParameter String accessId, @QueryParameter String secretKey, @QueryParameter String privateKey)
                throws IOException, ServletException {

            if (Util.fixEmpty(region) == null) {
                region = DEFAULT_EC2_HOST;
            }

            return super.doTestConnection(getEc2EndpointUrl(region), useInstanceProfileForCredentials, accessId, secretKey, privateKey);
        }

        public FormValidation doGenerateKey(StaplerResponse rsp, @QueryParameter String region, @QueryParameter boolean useInstanceProfileForCredentials, @QueryParameter String accessId, @QueryParameter String secretKey)
                throws IOException, ServletException {
            return super.doGenerateKey(rsp, getEc2EndpointUrl(region), useInstanceProfileForCredentials, accessId, secretKey);
        }
    }
}
