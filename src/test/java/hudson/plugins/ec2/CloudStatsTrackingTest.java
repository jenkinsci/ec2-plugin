package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Foundation tests for the opaque {@code cloud-stats} correlation id EC2 agents now carry. The core is
 * {@code cloud-stats}-free: an agent no longer holds a typed {@link ProvisioningActivity.Id}, only a plain persisted
 * {@link String} minted by the optional extension and resolved back to an activity by fingerprint. These assert the id
 * is carried, persisted across an XStream round-trip, and observably resolves to its activity -- with no reference to
 * {@code cloud-stats} types on the agent itself.
 */
@WithJenkins
class CloudStatsTrackingTest {

    // Injecting the JenkinsRule boots the Jenkins instance these tests need to construct EC2 agents (label parsing
    // and XStream both reach Jenkins.get()) and to look up the cloud-stats-backed tracker via EC2ProvisioningTracker.
    @SuppressWarnings("unused")
    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void agentIsUntrackedByDefault() throws Exception {
        EC2OndemandSlave slave = new EC2OndemandSlave("i-1234567890abcdef0");
        assertNull(
                slave.getCloudStatsCorrelationId(),
                "a freshly constructed agent should carry no cloud-stats correlation id yet");
    }

    @Test
    void agentCarriesPersistedCorrelationIdAcrossXStreamRoundTrip() throws Exception {
        EC2OndemandSlave slave = new EC2OndemandSlave("i-1234567890abcdef0");
        // The correlation id is an opaque string (a rendered activity fingerprint); core neither parses nor interprets
        // it, so any stable string round-trips the same way the real one does.
        slave.setCloudStatsCorrelationId("1234567");

        String xml = Jenkins.XSTREAM2.toXML(slave);
        EC2OndemandSlave restored = (EC2OndemandSlave) Jenkins.XSTREAM2.fromXML(xml);

        assertEquals(
                "1234567",
                restored.getCloudStatsCorrelationId(),
                "the opaque cloud-stats correlation id must survive an XStream round-trip unchanged");
    }

    /**
     * The observable contract that replaces the removed typed-id delegation: an agent carrying the correlation id an
     * activity was opened with resolves back to exactly that activity, matched purely by fingerprint. This is precisely
     * how the optional {@code cloud-stats} listeners rediscover an agent's activity from its persisted id.
     */
    @Test
    void correlationIdResolvesToItsActivity() throws Exception {
        // With cloud-stats on the test classpath the seam resolves to its real implementation, which opens an activity
        // and hands back the fingerprint-derived correlation id core persists.
        String correlationId = EC2ProvisioningTracker.get().onProvisioningStarted("myCloud", "myTemplate", "myNode");
        assertNotNull(correlationId, "the cloud-stats-backed tracker must mint a correlation id");

        EC2OndemandSlave slave = new EC2OndemandSlave("i-1234567890abcdef0");
        slave.setCloudStatsCorrelationId(correlationId);

        ProvisioningActivity activity = CloudStatsTestSupport.activityFor(slave);
        assertNotNull(activity, "the agent's correlation id must resolve to the activity it was opened with");
        assertEquals(
                correlationId,
                Integer.toString(activity.getId().getFingerprint()),
                "resolution is by fingerprint: the activity's fingerprint must render to the agent's correlation id");
    }

    @Test
    void untrackedAgentResolvesToNoActivity() throws Exception {
        EC2OndemandSlave slave = new EC2OndemandSlave("i-1234567890abcdef0");
        assertNull(
                CloudStatsTestSupport.activityFor(slave),
                "an agent with no correlation id must resolve to no activity, never throw");
    }
}
