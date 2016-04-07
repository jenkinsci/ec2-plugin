package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Descriptor;

import org.kohsuke.stapler.DataBoundConstructor;

public class UnixData extends AMITypeData {

    private final String rootCommandPrefix;
    private final String sshPort;

    @DataBoundConstructor
    public UnixData(String rootCommandPrefix, String sshPort) {
        this.rootCommandPrefix = rootCommandPrefix;
        this.sshPort = sshPort;
    }

    @Override
    public boolean isWindows() {
        return false;
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

    public String getRootCommandPrefix() {
        return rootCommandPrefix;
    }

    public String getSshPort() {
        return sshPort == null || sshPort.isEmpty() ? "22" : sshPort;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((rootCommandPrefix == null) ? 0 : rootCommandPrefix.hashCode());
        result = prime * result + ((sshPort == null) ? 0 : sshPort.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (this.getClass() != obj.getClass())
            return false;
        final UnixData other = (UnixData) obj;
        if (rootCommandPrefix == null) {
            if (other.rootCommandPrefix != null)
                return false;
        } else if (!rootCommandPrefix.equals(other.rootCommandPrefix))
            return false;
        if (sshPort == null) {
            if (other.sshPort != null)
                return false;
        } else if (!sshPort.equals(other.sshPort))
            return false;
        return true;
    }
}
