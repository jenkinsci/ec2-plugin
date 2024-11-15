package hudson.plugins.ec2;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;


public class WindowsSSHData extends SSHData {

    @DataBoundConstructor
    public WindowsSSHData(String rootCommandPrefix, String slaveCommandPrefix, String slaveCommandSuffix, String sshPort, String bootDelay) {
        super(rootCommandPrefix, slaveCommandPrefix, slaveCommandSuffix, sshPort, bootDelay);
    }

    @Override
    public boolean isWindowsSSH() {
        return true;
    }


    @Extension
    public static class DescriptorImpl extends Descriptor<AMITypeData> {
        @Override
        @NonNull
        public String getDisplayName() {
            return "windows-ssh";
        }
    }
}
