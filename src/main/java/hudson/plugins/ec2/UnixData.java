package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

public class UnixData extends AMITypeData {
    private final String rootCommandPrefix;
    private final String agentCommandPrefix;
    private final String agentCommandSuffix;
    private final String sshPort;
    private final String bootDelay;

    @DataBoundConstructor
    public UnixData(String rootCommandPrefix, String agentCommandPrefix, String agentCommandSuffix, String sshPort, String bootDelay) {
        this.rootCommandPrefix = rootCommandPrefix;
        this.agentCommandPrefix = agentCommandPrefix;
        this.agentCommandSuffix = agentCommandSuffix;
        this.sshPort = sshPort;
        this.bootDelay = bootDelay;

        this.readResolve();
    }

    protected Object readResolve() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            j.checkPermission(Jenkins.ADMINISTER);
        }
        return this;
    }

    @Override
    public boolean isWindows() {
        return false;
    }

    @Override
    public boolean isUnix() {
        return true;
    }

    @Override
    public boolean isMac() {
        return false;
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

    public String getAgentCommandPrefix() {
        return agentCommandPrefix;
    }

    public String getAgentCommandSuffix() {
        return agentCommandSuffix;
    }

    public String getSshPort() {
        return sshPort == null || sshPort.isEmpty() ? "22" : sshPort;
    }

    public String getBootDelay() {
        return bootDelay;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((rootCommandPrefix == null) ? 0 : rootCommandPrefix.hashCode());
        result = prime * result + ((agentCommandPrefix == null) ? 0 : agentCommandPrefix.hashCode());
        result = prime * result + ((agentCommandSuffix == null) ? 0 : agentCommandSuffix.hashCode());
        result = prime * result + ((sshPort == null) ? 0 : sshPort.hashCode());
        result = prime * result + ((bootDelay == null) ? 0 : bootDelay.hashCode());
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
        if (StringUtils.isEmpty(rootCommandPrefix)) {
            if (!StringUtils.isEmpty(other.rootCommandPrefix))
                return false;
        } else if (!rootCommandPrefix.equals(other.rootCommandPrefix))
            return false;
        if (StringUtils.isEmpty(agentCommandPrefix)) {
            if (!StringUtils.isEmpty(other.agentCommandPrefix))
                return false;
        } else if (!agentCommandPrefix.equals(other.agentCommandPrefix))
            return false;
        if (StringUtils.isEmpty(agentCommandSuffix)) {
            if (!StringUtils.isEmpty(other.agentCommandSuffix))
                return false;
        } else if (!agentCommandSuffix.equals(other.agentCommandSuffix))
            return false;
        if (StringUtils.isEmpty(sshPort)) {
            if (!StringUtils.isEmpty(other.sshPort))
                return false;
        } else if (!sshPort.equals(other.sshPort))
            return false;
        if (bootDelay == null) {
            if (other.bootDelay != null)
                return false;
        } else if (!bootDelay.equals(other.bootDelay))
            return false;
        return true;
    }
}
