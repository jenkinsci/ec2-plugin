package hudson.plugins.ec2;

import java.util.concurrent.TimeUnit;

import hudson.Extension;
import hudson.model.Descriptor;

import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

public class WindowsData extends AMITypeData {

    private final Secret password;
    private final boolean useHTTPS;
    private final String bootDelay;

    @DataBoundConstructor
    public WindowsData(String password, boolean useHTTPS, String bootDelay) {
        this.password = Secret.fromString(password);
        this.useHTTPS = useHTTPS;
        this.bootDelay = bootDelay;
    }

    public boolean isWindows() {
        return true;
    }

    public boolean isUnix() {
        return false;
    }

    public Secret getPassword() {
        return password;
    }

    public boolean isUseHTTPS() {
        return useHTTPS;
    }

    public String getBootDelay() {
        return bootDelay;
    }

    public int getBootDelayInMillis() {
        try {
            return (int) TimeUnit.SECONDS.toMillis(Integer.parseInt(bootDelay));
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AMITypeData> {
        public String getDisplayName() {
            return "windows";
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((bootDelay == null) ? 0 : bootDelay.hashCode());
        result = prime * result + ((password == null) ? 0 : password.hashCode());
        result = prime * result + (useHTTPS ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (!(obj instanceof WindowsData))
            return false;
        WindowsData other = (WindowsData) obj;
        if (bootDelay == null) {
            if (other.bootDelay != null)
                return false;
        } else if (!bootDelay.equals(other.bootDelay))
            return false;
        if (password == null) {
            if (other.password != null)
                return false;
        } else if (!password.equals(other.password))
            return false;
        if (useHTTPS != other.useHTTPS)
            return false;
        return true;
    }
}
