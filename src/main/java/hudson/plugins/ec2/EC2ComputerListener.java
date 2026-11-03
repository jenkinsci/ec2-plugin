package hudson.plugins.ec2;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;

@Extension
public class EC2ComputerListener extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(EC2ComputerListener.class.getName());

    /**
     * How long to keep asking EC2 why an instance went away.
     *
     * <p>The channel drops the moment the instance stops, which is before EC2 has settled on a
     * state for it, so the first look usually finds it still running and says nothing useful.
     */
    private static final long REASON_LOOKUP_TIMEOUT_MS =
            Long.getLong("jenkins.ec2.terminationReasonTimeoutMs", TimeUnit.MINUTES.toMillis(3));

    private static final long REASON_POLL_INTERVAL_MS = 10_000;

    /** What EC2 calls it in {@code stateReason} when it takes a spot instance back. */
    private static final Set<String> SPOT_RECLAMATION_CODES =
            Set.of("Server.SpotInstanceTermination", "Server.SpotInstanceShutdown");

    @Override
    public void onOnline(Computer c, TaskListener listener) {
        if (c instanceof EC2Computer) {
            ((EC2Computer) c).onConnected();
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            j.getQueue().scheduleMaintenance();
        }
    }

    @Override
    public void onOffline(@NonNull Computer c, @CheckForNull OfflineCause cause) {
        if (!(c instanceof EC2Computer ec2Computer)) {
            return;
        }
        /*
         * Only a channel that died on its own is worth explaining. That is the case with no reason
         * of its own, reported as an unexpected end of stream, and the one a reclaimed instance
         * produces. Every other cause already says who asked and why, including a shutdown Jenkins
         * itself ordered, and asking EC2 about those would put a second story over the true one.
         */
        if (!(cause instanceof OfflineCause.ChannelTermination)) {
            return;
        }
        EC2AbstractSlave node = ec2Computer.getNode();
        if (node == null) {
            return;
        }
        String instanceId = node.getInstanceId();
        EC2Cloud cloud = node.getCloud();
        if (instanceId == null || instanceId.isEmpty() || cloud == null) {
            return;
        }
        Computer.threadPoolForRemoting.submit(() -> explain(ec2Computer, cloud, instanceId));
    }

    /**
     * Replaces the channel error on a computer with what EC2 says became of its instance.
     *
     * <p>Only a terminated or stopping instance has anything to add. An agent that went offline
     * while its instance carries on running was lost for some reason of its own, and saying the
     * instance is running would be less informative than the error already there.
     */
    private static void explain(EC2Computer computer, EC2Cloud cloud, String instanceId) {
        long deadline = System.currentTimeMillis() + REASON_LOOKUP_TIMEOUT_MS;
        while (true) {
            try {
                Instance instance = CloudHelper.getInstance(instanceId, cloud);
                String reason = terminalReason(instance);
                if (reason != null) {
                    if (isSpotReclamation(instance)) {
                        EC2SpotInterruptions.note(computer.getName());
                    }
                    report(computer, instanceId, reason);
                    return;
                }
                if (System.currentTimeMillis() >= deadline) {
                    LOGGER.log(
                            Level.FINE,
                            "{0} went offline but EC2 never reported its instance {1} as gone",
                            new Object[] {computer.getName(), instanceId});
                    return;
                }
                Thread.sleep(REASON_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (SdkException e) {
                LOGGER.log(Level.FINE, "Could not ask EC2 why " + instanceId + " went away", e);
                return;
            }
        }
    }

    /**
     * @return what EC2 says about an instance that has gone, or {@code null} while it is still
     *     there or has not been given a reason yet.
     */
    @CheckForNull
    private static String terminalReason(@CheckForNull Instance instance) {
        if (instance == null) {
            // EC2 forgets a terminated instance entirely about an hour on, and a describe that
            // cannot find one at all is as final an answer as a terminated state.
            return "its instance is no longer known to EC2";
        }
        InstanceStateName state = instance.state().name();
        if (!InstanceStateName.TERMINATED.equals(state)
                && !InstanceStateName.SHUTTING_DOWN.equals(state)
                && !InstanceStateName.STOPPED.equals(state)
                && !InstanceStateName.STOPPING.equals(state)) {
            return null;
        }
        if (instance.stateReason() != null && instance.stateReason().message() != null) {
            String code = instance.stateReason().code();
            return code == null || code.isEmpty()
                    ? instance.stateReason().message()
                    : code + ": " + instance.stateReason().message();
        }
        String transition = instance.stateTransitionReason();
        if (transition != null && !transition.isEmpty()) {
            return transition;
        }
        return "its instance is " + state;
    }

    /**
     * @return whether EC2 says it took the instance back to satisfy spot capacity elsewhere, as
     *     opposed to the instance ending for any of the other reasons an agent can lose its host.
     *     That is the case worth retrying somewhere else, because nothing about the build caused it.
     */
    private static boolean isSpotReclamation(@CheckForNull Instance instance) {
        if (instance == null || instance.stateReason() == null) {
            return false;
        }
        return SPOT_RECLAMATION_CODES.contains(instance.stateReason().code());
    }

    private static void report(EC2Computer computer, String instanceId, String reason) {
        String message = "EC2 instance " + instanceId + " went away: " + reason;
        LOGGER.log(Level.INFO, "{0}: {1}", new Object[] {computer.getName(), message});
        TaskListener listener = computer.getListener();
        if (listener != null) {
            listener.error(message);
        }
        // Recorded as the offline cause as well as logged, because that is the part a pipeline is
        // shown when the node under it disappears.
        computer.disconnect(new EC2OfflineCause(message));
    }
}
