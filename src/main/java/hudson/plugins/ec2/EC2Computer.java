package hudson.plugins.ec2;

import hudson.slaves.SlaveComputer;

/**
 * @author Kohsuke Kawaguchi
 */
public class EC2Computer extends SlaveComputer {
    public EC2Computer(EC2Slave slave) {
        super(slave);
    }

    @Override
    public EC2Slave getNode() {
        return (EC2Slave)super.getNode();
    }
}
