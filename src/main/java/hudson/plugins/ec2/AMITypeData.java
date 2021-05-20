package hudson.plugins.ec2;

import hudson.model.AbstractDescribableImpl;

public abstract class AMITypeData extends AbstractDescribableImpl<AMITypeData> {
    public abstract boolean isWindows();

    public abstract boolean isUnix();

    public abstract boolean isMac();
}
