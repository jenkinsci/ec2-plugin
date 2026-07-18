package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import software.amazon.awssdk.services.ec2.model.InstanceType;

/**
 * Guards {@code cloud-stats} tracking across the multi-template provisioning path of {@link EC2Cloud#provision(Label,
 * int)}. When several templates match a label, the cloud tries them in order and falls back to a later template if an
 * earlier one has no capacity (see {@code EC2CloudTest#testMultipleTemplatesWithSameLabel} and the fallback fix it
 * covers). The cloud-stats identity is minted <em>inside</em> that per-template loop, so a regression there would let a
 * fallback-provisioned agent escape tracking even though a first-template agent is tracked.
 *
 * <p>These tests assert the external cloud-stats contract of that loop: every {@link PlannedNode} the cloud returns is
 * a {@link TrackedPlannedNode} (the marker cloud-stats keys on) carrying its own identity, and the fallback agent's
 * resolved node shares the activity's fingerprint -- no orphan activity, no orphan agent -- regardless of which
 * template produced it.
 */
@WithJenkins
class CloudStatsFallbackProvisioningTest {

    private static final String SHARED_LABEL = "fallbacklabel";

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        // A null mock forces a fresh Ec2Client (and an empty instance list) for this cap-sensitive test, so
        // instance-cap counting starts from zero and is not throttled by a mock/instances leaked by a previous test.
        AmazonEC2FactoryMockImpl.mock = null;
    }

    /**
     * The first matching template is exhausted (instance cap 0), so provisioning falls back to the second template.
     * The fallback agent must still be tracked: the returned planned node is a {@link TrackedPlannedNode} whose
     * activity names the fallback template, and the resolved agent shares the activity's single fingerprint.
     */
    @Test
    void fallbackToSecondTemplateTracksTheFallbackAgent() throws Exception {
        SlaveTemplate exhausted = template("ami-exhausted", "fallback-primary", 0);
        SlaveTemplate available = template("ami-available", "fallback-secondary", 10);
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, exhausted, available);

        Label label = r.jenkins.getLabel(SHARED_LABEL);
        Collection<PlannedNode> planned = cloud.provision(label, 1);
        assertEquals(1, planned.size(), "the exhausted template must be skipped and the fallback must provision one");

        TrackedPlannedNode tracked = assertInstanceOf(
                TrackedPlannedNode.class,
                planned.iterator().next(),
                "a fallback-provisioned planned node must still be tracked by cloud-stats");

        ProvisioningActivity activity =
                CloudStatistics.ProvisioningListener.get().onStarted(tracked.getId());
        assertEquals(ProvisioningActivity.Phase.PROVISIONING, activity.getCurrentPhase());
        assertEquals(1, CloudStatistics.get().getActivities().size(), "exactly one activity for the fallback agent");
        assertTrue(
                activity.getId().getTemplateName().contains("fallback-secondary"),
                "the activity must be attributed to the fallback template, but was: "
                        + activity.getId().getTemplateName());

        Node node = tracked.future.get(30, TimeUnit.SECONDS);
        assertNotNull(node, "the fallback planned agent must resolve to a real node");
        EC2AbstractSlave slave =
                assertInstanceOf(EC2AbstractSlave.class, node, "the fallback node must be an EC2 agent");
        assertNotNull(slave.getId(), "the resolved fallback agent must carry a cloud-stats identity");
        assertEquals(
                activity.getId().getFingerprint(),
                slave.getId().getFingerprint(),
                "the activity and the resolved fallback agent must share a single fingerprint");
    }

    /**
     * When one provisioning request spans two templates (the first caps out at one agent, the second supplies the
     * rest), every planned node the cloud returns must be a {@link TrackedPlannedNode} with its own distinct identity.
     * This guards the per-template list-index join against ever emitting an untracked node or reusing an id across the
     * template boundary.
     */
    @Test
    void everyPlannedNodeAcrossTemplatesIsTracked() {
        SlaveTemplate capOfOne = template("ami-cap1", "invariant-primary", 1);
        SlaveTemplate spillover = template("ami-cap10", "invariant-secondary", 10);
        EC2Cloud cloud = CloudStatsTestSupport.registerCloud(r, capOfOne, spillover);

        Label label = r.jenkins.getLabel(SHARED_LABEL);
        Collection<PlannedNode> planned = cloud.provision(label, 2 * capOfOne.getNumExecutors());
        assertEquals(2, planned.size(), "the capped template supplies one agent and the second supplies the other");

        assertTrue(
                planned.stream().allMatch(TrackedPlannedNode.class::isInstance),
                "every planned node across the template boundary must be tracked by cloud-stats");
        long distinctFingerprints = planned.stream()
                .map(p -> ((TrackedPlannedNode) p).getId().getFingerprint())
                .distinct()
                .count();
        assertEquals(2, distinctFingerprints, "each planned agent must carry its own distinct cloud-stats identity");

        // Two distinct fingerprints alone would also hold for two agents from a single template, so assert the two
        // agents carry the two different template names -- proving the request really spanned the template boundary
        // (the cap-of-one template served one, the second template served the other) rather than one template serving
        // both.
        long distinctTemplates = planned.stream()
                .map(p -> ((TrackedPlannedNode) p).getId().getTemplateName())
                .distinct()
                .count();
        assertEquals(
                2,
                distinctTemplates,
                "the two agents must be attributed to the two different templates, proving the request spanned the "
                        + "template boundary");
    }

    /**
     * Builds a template on the {@link #SHARED_LABEL} from the same known-good full constructor {@code MockEC2Computer}
     * uses, parameterising the AMI and description (so activities are attributable to a specific template). The
     * instance cap is set on the field afterwards -- this deprecated constructor's positional cap argument is not the
     * slot {@code MockEC2Computer}'s known-good arg list lands in -- and a cap of {@code 0} makes a template report
     * itself exhausted so provisioning falls through to the next.
     */
    // S1874 (deprecated constructor): deliberately the same deprecated SlaveTemplate constructor MockEC2Computer uses,
    // so these templates stay identically built to the other cloud-stats tests' templates.
    @SuppressWarnings("java:S1874")
    private static SlaveTemplate template(String ami, String description, int instanceCap) {
        SlaveTemplate template = new SlaveTemplate(
                ami,
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
                false,
                SHARED_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "1",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet-123 subnet-456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        template.instanceCap = instanceCap;
        return template;
    }
}
