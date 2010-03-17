package hudson.plugins.ec2;

import hudson.Extension;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * The original implementation of {@link EC2Cloud}.
 *
 * @author Kohsuke Kawaguchi
 */
public class AmazonEC2Cloud extends EC2Cloud {
    /**
     * Represents the region. Can be null for backward compatibility reasons.
     */
    private AwsRegion region;

    @DataBoundConstructor
    public AmazonEC2Cloud(AwsRegion region, String accessId, String secretKey, String privateKey, String instanceCapStr, List<SlaveTemplate> templates) {
        super("ec2-"+region.name(), accessId, secretKey, privateKey, instanceCapStr, templates);
        this.region = region;
    }

    public AwsRegion getRegion() {
        if (region==null)
            region = AwsRegion.US_EAST_1; // backward data compatibility with earlier versions
        return region;
    }

    @Override
    public URL getEc2EndpointUrl() {
        return getRegion().ec2Endpoint;
    }

    @Override
    public URL getS3EndpointUrl() {
        return getRegion().s3Endpoint;
    }

    @Extension
    public static class DescriptorImpl extends EC2Cloud.DescriptorImpl {
        public String getDisplayName() {
            return "Amazon EC2";
        }

        public FormValidation doTestConnection(
                 @QueryParameter AwsRegion region,
                 @QueryParameter String accessId,
                 @QueryParameter String secretKey,
                 @QueryParameter String privateKey) throws IOException, ServletException {
            return super.doTestConnection(region.ec2Endpoint,accessId,secretKey,privateKey);
        }

        public FormValidation doGenerateKey(
                StaplerResponse rsp, @QueryParameter AwsRegion region, @QueryParameter String accessId, @QueryParameter String secretKey) throws IOException, ServletException {
            return super.doGenerateKey(rsp,region.ec2Endpoint,accessId,secretKey);
        }
    }
}
