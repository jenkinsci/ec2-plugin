package hudson.plugins.ec2;

/**
 * Constants that represent the running state of EC2. 
 *
 * @author Kohsuke Kawaguchi
 */
public enum InstanceState {
    PENDING,
    RUNNING,
    SHUTTING_DOWN,
    TERMINATED,
    STOPPING,
    STOPPED;

    public String getCode() {
        return name().toLowerCase().replace('_','-');
    }

    public static InstanceState find(String name) {
        return Enum.valueOf(InstanceState.class,name.toUpperCase().replace('-','_'));
    }
}
