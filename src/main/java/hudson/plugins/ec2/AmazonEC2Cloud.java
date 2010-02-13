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

    @DataBoundConstructor
    public AmazonEC2Cloud(String accessId, String secretKey, String privateKey, String instanceCapStr, List<SlaveTemplate> templates) {
        super("ec2", accessId, secretKey, privateKey, instanceCapStr, templates);
    }

    @Override
    public URL getEc2EndpointUrl() {
        return EC2_ENDPOINT;
    }

    @Override
    public URL getS3EndpointUrl() {
        return S3_ENDPOINT;
    }

    @Extension
    public static class DescriptorImpl extends EC2Cloud.DescriptorImpl {
        public String getDisplayName() {
            return "Amazon EC2";
        }

        public FormValidation doTestConnection(
                 @QueryParameter String accessId,
                 @QueryParameter String secretKey,
                 @QueryParameter String privateKey) throws IOException, ServletException {
            return super.doTestConnection(EC2_ENDPOINT,accessId,secretKey,privateKey);
        }

        public FormValidation doGenerateKey(
                StaplerResponse rsp, @QueryParameter String accessId, @QueryParameter String secretKey) throws IOException, ServletException {
            return super.doGenerateKey(rsp,EC2_ENDPOINT,accessId,secretKey);
        }
    }


    private static final URL EC2_ENDPOINT;
    private static final URL S3_ENDPOINT;

    static {
        try {
            EC2_ENDPOINT = new URL("https://ec2.amazonaws.com/");
            S3_ENDPOINT = new URL("https://s3.amazonaws.com/");
        } catch (MalformedURLException e) {
            throw new Error(e);
        }
    }
}
