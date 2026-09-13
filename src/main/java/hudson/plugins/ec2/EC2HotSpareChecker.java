package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.PeriodicWork;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import java.util.concurrent.TimeUnit;

/**
 * Keeps hot spare counts current between the events that already trigger a check (a build becoming
 * buildable, a task being accepted, or the ten-minute {@link EC2SlaveMonitor} sweep). Those cover
 * the cases that matter for latency; this is the backstop that lets a label fade away when nothing
 * is happening at all.
 *
 * <p>This deliberately makes no provisioning decisions of its own: they all belong in the one
 * synchronized {@link MinimumInstanceChecker#checkForMinimumInstances()} pass.
 */
@Extension
public class EC2HotSpareChecker extends PeriodicWork {

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    protected void doRun() {
        MinimumInstanceChecker.scheduleCheck();
    }
}
