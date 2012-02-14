package hudson.plugins.ec2.ebs;

import hudson.model.AdministrativeMonitor;
import hudson.Extension;

/**
 * {@link AdministrativeMonitor} that tells the user that ZFS pool is filling up
 * and they need to add more storage.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class ZPoolExpandNotice extends AdministrativeMonitor {
    /**
     * Set by {@link ZPoolMonitor}.
     */
    /*package*/ boolean activated = false;

    public ZPoolExpandNotice() {
        super("zpool.ebs");
    }

    @Override
	public boolean isActivated() {
        return activated;
    }
}
