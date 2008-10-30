package hudson.plugins.ec2;

import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.util.FormFieldValidator;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.EC2Exception;

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
            new FormFieldValidator.Base64(req,rsp,false,false,Messages.EC2Cloud_InvalidAccessId()).process();
        }

        public void doCheckSecretKey(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            new FormFieldValidator.Base64(req,rsp,false,false,Messages.EC2Cloud_InvalidSecretKey()).process();
        }

        public void doTestConnection(StaplerRequest req, StaplerResponse rsp,
                                     @QueryParameter("accessId") final String accessId, @QueryParameter("secretKey") final String secretKey) throws IOException, ServletException {
            new FormFieldValidator(req,rsp,true) {
                protected void check() throws IOException, ServletException {
                    try {
                        Jec2 jec2 = new Jec2(accessId,Secret.fromString(secretKey).toString());
                        jec2.describeInstances(Collections.<String>emptyList());
                        ok(Messages.EC2Cloud_Success());
                    } catch (EC2Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to check EC2 credential",e);
                        error(e.getMessage());
                    }
                }
            }.process();
        }
    }

    static {
        ALL.add(DescriptorImpl.INSTANCE);
    }

    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());
}
