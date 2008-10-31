package hudson.plugins.ec2;

import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.slaves.Cloud;
import hudson.util.FormFieldValidator;
import hudson.util.Secret;
import hudson.scheduler.CronTabList;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.InvalidObjectException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.InstanceType;
import antlr.ANTLRException;

/**
 * Hudson's view of EC2. 
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2Cloud extends Cloud {
    private final String accessId;
    private final Secret secretKey;
    private final List<SlaveTemplate> templates;

    @DataBoundConstructor
    public EC2Cloud(String name, String accessId, String secretKey, List<SlaveTemplate> templates) {
        super(name);
        this.accessId = accessId.trim();
        this.secretKey = Secret.fromString(secretKey.trim());
        this.templates = templates;
        readResolve(); // set parents
    }

    protected Object readResolve() {
        for (SlaveTemplate t : templates)
            t.parent = this;
        return this;
    }

    public String getAccessId() {
        return accessId;
    }

    public String getSecretKey() {
        return secretKey.getEncryptedValue();
    }

    public List<SlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    public DescriptorImpl getDescriptor() {
        return DescriptorImpl.INSTANCE;
    }

    /**
     * Gets the first {@link EC2Cloud} instance configured in the current Hudson, or null if no such thing exists.
     */
    public static EC2Cloud get() {
        return (EC2Cloud)Hudson.getInstance().clouds.get(DescriptorImpl.INSTANCE);
    }

    /**
     * Connects to EC2 and returns {@link Jec2}, which can then be used to communicate with EC2.
     */
    public Jec2 connect() {
        return new Jec2(accessId,secretKey.toString());
    }

    public static final class DescriptorImpl extends Descriptor<Cloud> {
        public static final DescriptorImpl INSTANCE = new DescriptorImpl();

        private DescriptorImpl() {
            super(EC2Cloud.class);
        }

        public String getDisplayName() {
            return "Amazon EC2";
        }

        public InstanceType[] getInstanceTypes() {
            return InstanceType.values();
        }

        public void doCheckAccessId(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            new FormFieldValidator.Base64(req,rsp,false,false,Messages.EC2Cloud_InvalidAccessId()).process();
        }

        public void doCheckSecretKey(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            new FormFieldValidator.Base64(req,rsp,false,false,Messages.EC2Cloud_InvalidSecretKey()).process();
        }

        public void doTestConnection(StaplerRequest req, StaplerResponse rsp,
                                     @QueryParameter final String accessId, @QueryParameter final String secretKey) throws IOException, ServletException {
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
