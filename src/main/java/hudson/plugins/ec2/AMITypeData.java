package hudson.plugins.ec2;

import hudson.model.AbstractDescribableImpl;

import java.util.concurrent.TimeUnit;

public abstract class AMITypeData extends AbstractDescribableImpl<AMITypeData> {

    public abstract boolean isWindowsSSH();

    public abstract boolean isWindows();

    public abstract boolean isUnix();

    public abstract boolean isMac();

    public abstract String getBootDelay();

    public int getBootDelayInMillis() {
        if (getBootDelay() == null)
            return 0;
        try {
            return (int) TimeUnit.SECONDS.toMillis(Integer.parseInt(getBootDelay()));
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

}
