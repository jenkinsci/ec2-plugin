package hudson.plugins.ec2;

import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

public abstract class SSHData extends AMITypeData {
    protected final String rootCommandPrefix;
    protected final String slaveCommandPrefix;
    protected final String slaveCommandSuffix;
    protected final String sshPort;
    protected final String bootDelay;

    protected SSHData(
            String rootCommandPrefix,
            String slaveCommandPrefix,
            String slaveCommandSuffix,
            String sshPort,
            String bootDelay) {
        this.rootCommandPrefix = rootCommandPrefix;
        this.slaveCommandPrefix = slaveCommandPrefix;
        this.slaveCommandSuffix = slaveCommandSuffix;
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
        return false;
    }

    @Override
    public boolean isMac() {
        return false;
    }

    @Override
    public boolean isSSHAgent() {
        return true;
    }

    @Override
    public boolean isWinRMAgent() {
        return false;
    }

    public String getRootCommandPrefix() {
        return rootCommandPrefix;
    }

    public String getSlaveCommandPrefix() {
        return slaveCommandPrefix;
    }

    public String getSlaveCommandSuffix() {
        return slaveCommandSuffix;
    }

    public String getSshPort() {
        return sshPort == null || sshPort.isEmpty() ? "22" : sshPort;
    }

    @Override
    public String getBootDelay() {
        return bootDelay;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((rootCommandPrefix == null) ? 0 : rootCommandPrefix.hashCode());
        result = prime * result + ((slaveCommandPrefix == null) ? 0 : slaveCommandPrefix.hashCode());
        result = prime * result + ((slaveCommandSuffix == null) ? 0 : slaveCommandSuffix.hashCode());
        result = prime * result + ((sshPort == null) ? 0 : sshPort.hashCode());
        result = prime * result + ((bootDelay == null) ? 0 : bootDelay.hashCode());
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
        final SSHData other = (SSHData) obj;
        if (StringUtils.isEmpty(rootCommandPrefix)) {
            if (!StringUtils.isEmpty(other.rootCommandPrefix)) {
                return false;
            }
        } else if (!rootCommandPrefix.equals(other.rootCommandPrefix)) {
            return false;
        }
        if (StringUtils.isEmpty(slaveCommandPrefix)) {
            if (!StringUtils.isEmpty(other.slaveCommandPrefix)) {
                return false;
            }
        } else if (!slaveCommandPrefix.equals(other.slaveCommandPrefix)) {
            return false;
        }
        if (StringUtils.isEmpty(slaveCommandSuffix)) {
            if (!StringUtils.isEmpty(other.slaveCommandSuffix)) {
                return false;
            }
        } else if (!slaveCommandSuffix.equals(other.slaveCommandSuffix)) {
            return false;
        }
        if (StringUtils.isEmpty(sshPort)) {
            if (!StringUtils.isEmpty(other.sshPort)) {
                return false;
            }
        } else if (!sshPort.equals(other.sshPort)) {
            return false;
        }
        if (bootDelay == null) {
            if (other.bootDelay != null) {
                return false;
            }
        } else if (!bootDelay.equals(other.bootDelay)) {
            return false;
        }
        return true;
    }
}
