package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit tests for the {@code cloud-stats}-free provisioning-tracker seam.
 *
 * <p>{@code cloud-stats} is on the test classpath, so the {@code @OptionalExtension(requirePlugins = "cloud-stats")}
 * implementation registers and {@link EC2ProvisioningTracker#get()} resolves to the real, cloud-stats-backed tracker.
 * These tests assert that seam's observable contract against cloud-stats 423: a "started" operation mints a non-null
 * correlation id that resolves to a real {@code CloudStatistics} activity, and null or unrecognised ids are safe
 * no-ops. The load-safe fallback used when {@code cloud-stats} is absent -- which cannot be exercised while the plugin
 * is installed -- is covered directly against the private {@link EC2ProvisioningTracker} {@code NoOp} singleton.
 */
@WithJenkins
class EC2ProvisioningTrackerTest {

    // ExtensionList.lookup (inside EC2ProvisioningTracker.get()) and CloudStatistics both need a running Jenkins.
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

    /**
     * With {@code cloud-stats} installed, {@link EC2ProvisioningTracker#get()} resolves to the real tracker, so both
     * the nameless (async / step) and the named (bypass) "started" operations must mint a non-null correlation id that
     * resolves back to a real {@code CloudStatistics} activity by fingerprint.
     */
    @Test
    void startedOpsMintResolvableCorrelationIdWhenCloudStatsInstalled() {
        EC2ProvisioningTracker tracker = EC2ProvisioningTracker.get();

        String namelessId = tracker.onProvisioningStarted("myCloud", "myTemplate");
        assertNotNull(namelessId, "the cloud-stats-backed tracker must mint a correlation id for a nameless start");
        assertNotNull(
                CloudStatsTestSupport.activityForCorrelationId(namelessId),
                "the nameless-start correlation id must resolve to a real activity by fingerprint");

        String namedId = tracker.onProvisioningStarted("myCloud", "myTemplate", "myNode");
        assertNotNull(namedId, "the cloud-stats-backed tracker must mint a correlation id for a named start");
        assertNotNull(
                CloudStatsTestSupport.activityForCorrelationId(namedId),
                "the named-start correlation id must resolve to a real activity by fingerprint");
    }

    /**
     * The report operations must tolerate a {@code null} correlation id (what a start returns when no tracker is
     * active) and an id that matches no activity, treating both as silent no-ops rather than throwing.
     */
    @Test
    void nullAndUnknownCorrelationIdsAreSafeNoOps() {
        EC2ProvisioningTracker tracker = EC2ProvisioningTracker.get();
        assertDoesNotThrow(() -> tracker.onProvisioningFailed(null, "no capacity"));
        assertDoesNotThrow(() -> tracker.onProvisioningFailed("not-a-fingerprint", "no capacity"));
        assertDoesNotThrow(() -> tracker.onProvisioningCompleted(null));
        assertDoesNotThrow(() -> tracker.onProvisioningCompleted("not-a-fingerprint"));
    }

    /**
     * When {@code cloud-stats} is absent the extension list is empty and {@link EC2ProvisioningTracker#get()} falls
     * back to the silent {@code NoOp}: every start yields a {@code null} correlation id and every report is dropped
     * without throwing. That branch cannot be reached while cloud-stats is installed, so the fallback singleton is
     * exercised directly here -- the one honest way to cover the plugin's load-safe-without-cloud-stats contract in an
     * environment where cloud-stats is present.
     */
    @Test
    void absentCloudStatsFallsBackToSilentNoOp() throws Exception {
        EC2ProvisioningTracker noOp = noOpFallback();
        assertNull(noOp.onProvisioningStarted("myCloud", "myTemplate"), "the no-op start must yield a null id");
        assertNull(
                noOp.onProvisioningStarted("myCloud", "myTemplate", "myNode"),
                "the no-op named start must yield a null id");
        assertDoesNotThrow(() -> noOp.onProvisioningFailed(null, "no capacity"));
        assertDoesNotThrow(() -> noOp.onProvisioningFailed("some-correlation-id", "no capacity"));
        assertDoesNotThrow(() -> noOp.onProvisioningCompleted(null));
        assertDoesNotThrow(() -> noOp.onProvisioningCompleted("some-correlation-id"));
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

    /**
     * Returns the private {@code NoOp} fallback singleton {@link EC2ProvisioningTracker#get()} would hand back when the
     * extension list is empty. Reached reflectively because the fallback is deliberately package-invisible (it is never
     * an {@code @Extension}); testing the exact singleton keeps this honest to what production returns.
     */
    private static EC2ProvisioningTracker noOpFallback() throws Exception {
        for (Class<?> nested : EC2ProvisioningTracker.class.getDeclaredClasses()) {
            if ("NoOp".equals(nested.getSimpleName())) {
                Field instance = nested.getDeclaredField("INSTANCE");
                instance.setAccessible(true);
                return (EC2ProvisioningTracker) instance.get(null);
            }
        }
        throw new AssertionError("EC2ProvisioningTracker.NoOp fallback singleton not found");
    }
}
