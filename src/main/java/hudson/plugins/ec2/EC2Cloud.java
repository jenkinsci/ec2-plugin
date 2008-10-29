package hudson.plugins.ec2;

import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.util.FormFieldValidator;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
public class EC2Cloud extends Cloud {
    private final String accessId;
    private final Secret secretKey;

    @DataBoundConstructor
    public EC2Cloud(String name, String accessId, String secretKey) {
        super(name);
        this.accessId = accessId.trim();
        this.secretKey = Secret.fromString(secretKey.trim());
    }

    public String getAccessId() {
        return accessId;
    }

    public String getSecretKey() {
        return secretKey.getEncryptedValue();
    }

    public DescriptorImpl getDescriptor() {
        return DescriptorImpl.INSTANCE;
    }

    public static final class DescriptorImpl extends Descriptor<Cloud> {
        public static final DescriptorImpl INSTANCE = new DescriptorImpl();

        private DescriptorImpl() {
            super(EC2Cloud.class);
        }

        public String getDisplayName() {
            return "Amazon EC2";
        }

        public void doCheckAccessId(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            new FormFieldValidator.Base64(req,rsp,false,false,"Invalid AWS access key ID").process();
        }

        public void doCheckSecretKey(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            new FormFieldValidator.Base64(req,rsp,false,false,"Invalid AWS secret access key").process();
        }
    }

    static {
        ALL.add(DescriptorImpl.INSTANCE);
    }

    
}
