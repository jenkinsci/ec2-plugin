package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit tests for the {@code cloud-stats}-free provisioning-tracker seam. This issue introduces the seam beside the
 * existing typed integration but wires no implementation, so with no {@code cloud-stats}-backed tracker on the
 * extension list every lookup resolves to the silent no-op: the contract that lets the always-loaded core call the
 * tracker unconditionally.
 */
@WithJenkins
class EC2ProvisioningTrackerTest {

    // ExtensionList.lookup (inside EC2ProvisioningTracker.get()) needs a running Jenkins.
    @SuppressWarnings("unused")
    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void getNeverReturnsNull() {
        assertNotNull(EC2ProvisioningTracker.get(), "get() must always return a tracker, real or no-op");
    }

    @Test
    void startedOpsReturnNullCorrelationIdWhenNoTrackerRegistered() {
        EC2ProvisioningTracker tracker = EC2ProvisioningTracker.get();
        assertNull(
                tracker.onProvisioningStarted("myCloud", "myTemplate"),
                "with no tracker installed the planned-agent start must yield a null correlation id");
        assertNull(
                tracker.onProvisioningStarted("myCloud", "myTemplate", "myNode"),
                "with no tracker installed the known-node start must yield a null correlation id");
    }

    @Test
    void failureAndCompletionAreSilentNoOpsWhenNoTrackerRegistered() {
        EC2ProvisioningTracker tracker = EC2ProvisioningTracker.get();
        // A null correlation id (what the started ops just returned) must be safe to hand back to every op.
        assertDoesNotThrow(() -> tracker.onProvisioningFailed(null, "no capacity"));
        assertDoesNotThrow(() -> tracker.onProvisioningFailed("some-correlation-id", "no capacity"));
        assertDoesNotThrow(() -> tracker.onProvisioningCompleted(null));
        assertDoesNotThrow(() -> tracker.onProvisioningCompleted("some-correlation-id"));
    }

    /**
     * The seam's whole purpose is to be callable from the always-loaded core, so its own API must name no
     * {@code cloud-stats} type. Guards the acceptance criterion directly; the broader constant-pool scan of the
     * always-loaded core lives in its own structural test.
     */
    @Test
    void apiSurfaceReferencesNoCloudStatsType() {
        for (Method m : EC2ProvisioningTracker.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            assertFalse(
                    isCloudStats(m.getReturnType()),
                    () -> m.getName() + " returns a cloud-stats type: " + m.getReturnType());
            for (Class<?> param : m.getParameterTypes()) {
                assertFalse(isCloudStats(param), () -> m.getName() + " takes a cloud-stats type: " + param);
            }
        }
    }

    private static boolean isCloudStats(Class<?> type) {
        return type.getName().startsWith("org.jenkinsci.plugins.cloudstats");
    }
}
