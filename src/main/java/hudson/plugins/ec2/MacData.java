package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

public class MacData extends SSHData {
    @DataBoundConstructor
    public MacData(
            String rootCommandPrefix,
            String slaveCommandPrefix,
            String slaveCommandSuffix,
            String sshPort,
            String bootDelay) {
        super(rootCommandPrefix, slaveCommandPrefix, slaveCommandSuffix, sshPort, bootDelay);
    }

    @Override
    protected Object readResolve() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return this;
    }

    @Override
    public boolean isMac() {
        return true;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AMITypeData> {
        @Override
        public String getDisplayName() {
            return "mac";
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((rootCommandPrefix == null) ? 0 : rootCommandPrefix.hashCode());
        result = prime * result + ((slaveCommandPrefix == null) ? 0 : slaveCommandPrefix.hashCode());
        result = prime * result + ((slaveCommandSuffix == null) ? 0 : slaveCommandSuffix.hashCode());
        result = prime * result + ((sshPort == null) ? 0 : sshPort.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }
        final MacData other = (MacData) obj;
        if (rootCommandPrefix == null || rootCommandPrefix.isEmpty()) {
            if (other.rootCommandPrefix != null && !other.rootCommandPrefix.isEmpty()) {
                return false;
            }
        } else if (!rootCommandPrefix.equals(other.rootCommandPrefix)) {
            return false;
        }
        if (slaveCommandPrefix == null || slaveCommandPrefix.isEmpty()) {
            if (other.slaveCommandPrefix != null && !other.slaveCommandPrefix.isEmpty()) {
                return false;
            }
        } else if (!slaveCommandPrefix.equals(other.slaveCommandPrefix)) {
            return false;
        }
        if (slaveCommandSuffix == null || slaveCommandSuffix.isEmpty()) {
            if (other.slaveCommandSuffix != null && !other.slaveCommandSuffix.isEmpty()) {
                return false;
            }
        } else if (!slaveCommandSuffix.equals(other.slaveCommandSuffix)) {
            return false;
        }
        if (sshPort == null || sshPort.isEmpty()) {
            if (other.sshPort != null && !other.sshPort.isEmpty()) {
                return false;
            }
        } else if (!sshPort.equals(other.sshPort)) {
            return false;
        }
        return true;
    }
}
