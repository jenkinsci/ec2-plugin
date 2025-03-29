package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class UnixData extends SSHData {
    @DataBoundConstructor
    public UnixData(
            String rootCommandPrefix,
            String slaveCommandPrefix,
            String slaveCommandSuffix,
            String sshPort,
            String bootDelay) {
        super(rootCommandPrefix, slaveCommandPrefix, slaveCommandSuffix, sshPort, bootDelay);
    }

    @Override
    public boolean isUnix() {
        return true;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AMITypeData> {
        @Override
        public String getDisplayName() {
            return "unix";
        }
    }
}
