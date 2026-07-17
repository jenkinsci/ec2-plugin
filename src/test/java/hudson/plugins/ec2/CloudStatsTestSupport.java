package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.fail;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.jenkinsci.plugins.cloudstats.PhaseExecution;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Shared fixtures for the {@code cloud-stats} integration tests: a helper to register a real {@link EC2Cloud}, a
 * poll-until helper for cloud-stats' asynchronous phase advancement, and a concrete {@link EC2AbstractSlave} that
 * launches through a substituted local {@link ComputerLauncher} instead of the AWS SSH launchers the production
 * subclasses hardcode.
 */
final class CloudStatsTestSupport {

    private CloudStatsTestSupport() {}

    /** Registers a real {@link EC2Cloud} (with SSH credentials available) that offers the given template. */
    static EC2Cloud registerCloud(JenkinsRule r, SlaveTemplate template) {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        EC2Cloud cloud = new EC2Cloud(
                "testcloud",
                true,
                "abc",
                "us-east-1",
                null,
                "ghi",
                "10",
                Collections.singletonList(template),
                null,
                null);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    /** Waits briefly for {@code cloud-stats} to record {@code phase}, tolerating any asynchrony in phase advancement. */
    // S2925 (Thread.sleep): standard poll-until-recorded loop for cloud-stats' asynchronous phase advancement;
    // a short sleep between polls is the idiom, and Awaitility is not on the test classpath.
    @SuppressWarnings("java:S2925")
    static void awaitPhase(ProvisioningActivity activity, ProvisioningActivity.Phase phase)
            throws InterruptedException {
        for (int i = 0; i < 200 && activity.getPhaseExecution(phase) == null; i++) {
            Thread.sleep(50);
        }
        if (activity.getPhaseExecution(phase) == null) {
            fail("Timed out after 10s waiting for cloud-stats to record phase " + phase);
        }
    }

    /** The first {@code FAIL} attachment recorded on any phase of {@code activity}, or {@code null} if none. */
    static PhaseExecutionAttachment failAttachment(ProvisioningActivity activity) {
        for (PhaseExecution execution : activity.getPhaseExecutions().values()) {
            if (execution == null) {
                continue; // phases not yet entered map to a null execution
            }
            for (PhaseExecutionAttachment attachment : execution.getAttachments()) {
                if (attachment.getStatus() == ProvisioningActivity.Status.FAIL) {
                    return attachment;
                }
            }
        }
        return null;
    }

    /**
     * A minimal concrete {@link EC2AbstractSlave} that launches through a substituted, real-but-local
     * {@link ComputerLauncher} (so it can reach OPERATING, or fail to launch) instead of the AWS SSH launchers the
     * production subclasses hardcode. It carries no live EC2 instance, so its retention is a no-op and retirement is
     * driven by the test.
     */
    public static class LocalLaunchSlave extends EC2AbstractSlave {

        @SuppressWarnings("unchecked")
        LocalLaunchSlave(String name, String remoteFS, ComputerLauncher launcher)
                throws Descriptor.FormException, IOException {
            super(
                    name,
                    "i-" + name,
                    "local launch test agent",
                    remoteFS,
                    1,
                    Mode.NORMAL,
                    "",
                    launcher,
                    // NOOP never retires the agent; the test controls retirement via removeNode. A raw narrowing is
                    // required because NOOP is typed RetentionStrategy<Computer> and generics are invariant.
                    (RetentionStrategy<EC2Computer>) (RetentionStrategy<?>) RetentionStrategy.NOOP,
                    "",
                    remoteFS,
                    Collections.emptyList(),
                    "",
                    DEFAULT_JAVA_PATH,
                    "",
                    false,
                    "0",
                    null,
                    "testcloud",
                    -1,
                    null,
                    ConnectionStrategy.PRIVATE_IP,
                    -1,
                    Tenancy.Default,
                    DEFAULT_METADATA_ENDPOINT_ENABLED,
                    DEFAULT_METADATA_TOKENS_REQUIRED,
                    DEFAULT_METADATA_HOPS_LIMIT,
                    DEFAULT_METADATA_SUPPORTED,
                    DEFAULT_ENCLAVE_ENABLED);
        }

        @Override
        public Future<?> terminate() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String getEc2Type() {
            return "LocalLaunch";
        }

        @Extension
        public static class DescriptorImpl extends EC2AbstractSlave.DescriptorImpl {
            @Override
            public String getDisplayName() {
                return "LocalLaunchSlave";
            }
        }
    }
}
