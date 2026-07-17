package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Foundation tests for the {@code cloud-stats} tracked-identity added to EC2 agents. No provisioning path mints or
 * advances an activity yet; these only assert that the identity is carried, persisted, and delegated.
 */
@WithJenkins
class CloudStatsTrackingTest {

    // Injecting the JenkinsRule boots the Jenkins instance these tests need to construct EC2 agents (label parsing
    // and XStream both reach Jenkins.get()); the field also mirrors the idiom the sibling CloudStats* tests use.
    @SuppressWarnings("unused")
    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void agentIsUntrackedByDefault() throws Exception {
        EC2OndemandSlave slave = new EC2OndemandSlave("i-1234567890abcdef0");
        assertNull(slave.getId(), "a freshly constructed agent should carry no cloud-stats identity yet");
    }

    @Test
    void agentCarriesPersistedIdAcrossXStreamRoundTrip() throws Exception {
        EC2OndemandSlave slave = new EC2OndemandSlave("i-1234567890abcdef0");
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("myCloud", "myTemplate", "myNode");
        slave.setCloudStatsId(id);

        String xml = Jenkins.XSTREAM2.toXML(slave);
        EC2OndemandSlave restored = (EC2OndemandSlave) Jenkins.XSTREAM2.fromXML(xml);

        ProvisioningActivity.Id restoredId = restored.getId();
        assertNotNull(restoredId, "the cloud-stats identity must survive an XStream round-trip");
        // Identity is the fingerprint; equality is defined purely by it.
        assertEquals(id.getFingerprint(), restoredId.getFingerprint(), "fingerprint must round-trip unchanged");
        assertEquals(id, restoredId);
        assertEquals("myCloud", restoredId.getCloudName());
        assertEquals("myTemplate", restoredId.getTemplateName());
        assertEquals("myNode", restoredId.getNodeName());
    }

    @Test
    void computerDelegatesIdToItsNode() throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("delegate");
        assertNull(computer.getId(), "an untracked node yields an untracked computer");

        ProvisioningActivity.Id id = new ProvisioningActivity.Id("cloud", "template", "node");
        computer.getNode().setCloudStatsId(id);
        assertEquals(id, computer.getId(), "the computer must delegate its identity to its node");
    }

    @Test
    void computerWithoutNodeReturnsNullId() {
        EC2Computer computer = mock(EC2Computer.class);
        when(computer.getNode()).thenReturn(null);
        when(computer.getId()).thenCallRealMethod();
        assertNull(computer.getId(), "a computer whose node is gone must return a null identity, never throw");
    }
}
