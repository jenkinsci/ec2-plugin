package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import software.amazon.awssdk.services.ec2.model.InstanceType;

/**
 * A {@link SlaveTemplate}'s instance cap, and the cloud-wide cap, are hard ceilings. Label hot
 * spare numbers and label-driven provisioning can only stay under them: they must never be read as
 * permission to launch more instances from a template that is already full.
 */
@WithJenkins
class EC2CloudInstanceCapTest {

    private static final String LABEL = "linux";
    private static final InstanceType FIRST_TYPE = InstanceType.T2_MICRO;
    private static final InstanceType SECOND_TYPE = InstanceType.M1_LARGE;

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        AmazonEC2FactoryMockImpl.mock = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
    }

    /**
     * A rule asking for more spares than a template may hold gets what the cap allows and nothing
     * more, however often the check runs.
     */
    @Test
    void testHotSpareNumbersCannotExceedATemplateInstanceCap() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(5);
        rule.setMaxHotSpares(10);
        EC2Cloud cloud = cloud(false, rule, template("only", FIRST_TYPE, 1, 1));

        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(agentsByTemplate(), equalTo(Map.of("only", 1L)));
        assertThat(cloud.getAvailableCapacity(cloud.getTemplates().get(0)), equalTo(0));
    }

    /**
     * The shortfall a full template cannot take is offered to the templates that still have room,
     * so the group reaches the requested number as long as the caps allow it in total.
     */
    @Test
    void testGroupShortfallIsRedistributedToTemplatesWithHeadroom() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(4);
        cloud(false, rule, template("small", FIRST_TYPE, 1, 1), template("large", SECOND_TYPE, 3, 1));

        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(agentsByTemplate(), equalTo(Map.of("small", 1L, "large", 3L)));
    }

    /**
     * A weight only decides who is asked first. Once that template is full, the rest of the group
     * supplies the spares rather than the label going short.
     */
    @Test
    void testPreferredTemplateAtItsCapYieldsToLowerWeightedOnes() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(4);
        SlaveTemplate preferred = template("preferred", FIRST_TYPE, 1, 3);
        SlaveTemplate fallback = template("fallback", SECOND_TYPE, 5, 1);
        EC2Cloud cloud = cloud(true, rule, preferred, fallback);

        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(agentsByTemplate(), equalTo(Map.of("preferred", 1L, "fallback", 3L)));
        assertFalse(
                cloud.getRotation().isTemplateInCooldown(preferred, 2),
                "a full template is not an exhausted instance type, so it must not be demoted");
    }

    /**
     * Sequential label requests must each see the instances the previous one launched, otherwise
     * the 30 second instance count cache would let a template be filled past its cap.
     */
    @Test
    void testSequentialLabelRequestsStopAtTheTemplateCap() throws Exception {
        SlaveTemplate first = template("first", FIRST_TYPE, 1, 1);
        SlaveTemplate second = template("second", SECOND_TYPE, 1, 1);
        cloud(false, null, first, second);
        EC2Cloud cloud = r.jenkins.clouds.get(EC2Cloud.class);

        cloud.provision(Label.get(LABEL), 1);
        awaitInstanceCount(1);
        cloud.provision(Label.get(LABEL), 1);
        awaitInstanceCount(2);
        cloud.provision(Label.get(LABEL), 1);

        assertThat(
                "both templates were at their cap of one, so the third request has nowhere to go",
                settledInstanceCount(),
                equalTo(2));
        assertThat(instanceTypeCounts(), equalTo(Map.of(FIRST_TYPE.toString(), 1L, SECOND_TYPE.toString(), 1L)));
    }

    /**
     * A launch counts against the headroom as soon as it happens, even though the instance count
     * cache still holds the count from before it and EC2 may not report it yet. That is what keeps
     * requests arriving inside the cache window from filling a template past its cap.
     */
    @Test
    void testHeadroomReflectsALabelRequestWithoutWaitingForTheCacheToExpire() throws Exception {
        SlaveTemplate template = template("only", FIRST_TYPE, 2, 1);
        EC2Cloud cloud = cloud(false, null, template);
        assertThat(cloud.getAvailableCapacity(cloud.getTemplates().get(0)), equalTo(2));

        cloud.provision(Label.get(LABEL), 1);
        awaitInstanceCount(1);

        // Well inside the cache TTL, so a stale count on its own would still report two.
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (cloud.getAvailableCapacity(cloud.getTemplates().get(0)) == 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(cloud.getAvailableCapacity(cloud.getTemplates().get(0)), equalTo(1));
    }

    /**
     * Picking up an orphaned instance is not a new launch. Once EC2 reports that instance, counting
     * it again as in flight would charge one instance twice and starve the cloud of headroom it
     * still has.
     */
    @Test
    void testReusedInstanceIsNotCountedTwice() throws Exception {
        SlaveTemplate reusing = template("reusing", FIRST_TYPE, 5, 1);
        reusing.setAvoidUsingOrphanedNodes(false);
        SlaveTemplate other = template("other", SECOND_TYPE, 5, 1);
        EC2Cloud cloud = cloud("2", false, null, reusing, other);

        cloud.provision(cloud.getTemplates().get(0), 1);
        assertThat(instanceCount(), equalTo(1));

        // Orphan it: the instance keeps running, but Jenkins no longer has a node for it.
        for (Node node : new ArrayList<>(r.jenkins.getNodes())) {
            r.jenkins.removeNode(node);
        }
        // Counting the second template is a cache miss, so this reads the instance back from EC2.
        assertThat(cloud.getAvailableCapacity(cloud.getTemplates().get(1)), equalTo(1));

        cloud.provision(cloud.getTemplates().get(0), 1);

        assertThat("the orphan should have been adopted rather than replaced", instanceCount(), equalTo(1));
        assertThat(
                "one running instance against a cloud cap of two leaves one",
                cloud.getAvailableCapacity(cloud.getTemplates().get(0)),
                equalTo(1));
    }

    /**
     * The cloud-wide cap is the other ceiling a label cannot lift, even with templates that still
     * have headroom of their own.
     */
    @Test
    void testSequentialLabelRequestsStopAtTheCloudWideCap() throws Exception {
        cloud("2", false, null, template("first", FIRST_TYPE, 10, 1), template("second", SECOND_TYPE, 10, 1));
        EC2Cloud cloud = r.jenkins.clouds.get(EC2Cloud.class);

        cloud.provision(Label.get(LABEL), 1);
        awaitInstanceCount(1);
        cloud.provision(Label.get(LABEL), 1);
        awaitInstanceCount(2);
        cloud.provision(Label.get(LABEL), 1);

        assertThat("the cloud instance cap of two must hold", settledInstanceCount(), equalTo(2));
    }

    /**
     * A request for more agents than the group can hold is served up to the caps instead of being
     * refused or overshooting.
     */
    @Test
    void testOneLabelRequestForMoreThanTheCapsAllowIsClampedToTheCaps() throws Exception {
        cloud(false, null, template("first", FIRST_TYPE, 1, 1), template("second", SECOND_TYPE, 2, 1));
        EC2Cloud cloud = r.jenkins.clouds.get(EC2Cloud.class);

        cloud.provision(Label.get(LABEL), 5);

        awaitInstanceCount(3);
        assertThat(settledInstanceCount(), equalTo(3));
        assertThat(instanceTypeCounts(), equalTo(Map.of(FIRST_TYPE.toString(), 1L, SECOND_TYPE.toString(), 2L)));
    }

    private static Map<String, Long> agentsByTemplate() {
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .map(EC2Computer::getNode)
                .filter(node -> node != null)
                .collect(Collectors.groupingBy(node -> node.templateDescription, Collectors.counting()));
    }

    private static int instanceCount() {
        return AmazonEC2FactoryMockImpl.instances.size();
    }

    private static Map<String, Long> instanceTypeCounts() {
        return AmazonEC2FactoryMockImpl.instances.stream()
                .collect(Collectors.groupingBy(instance -> instance.instanceTypeAsString(), Collectors.counting()));
    }

    private static void awaitInstanceCount(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            if (AmazonEC2FactoryMockImpl.instances.size() >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        fail("only " + AmazonEC2FactoryMockImpl.instances.size() + " of " + expected + " instances were launched");
    }

    /**
     * @return the instance count once provisioning has had time to launch anything it was going to.
     *     Asserting that nothing was launched needs a wait, not just a read.
     */
    private static int settledInstanceCount() throws InterruptedException {
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
        return AmazonEC2FactoryMockImpl.instances.size();
    }

    private EC2Cloud cloud(boolean roundRobin, HotSpareConfigByLabel rule, SlaveTemplate... templates)
            throws Exception {
        return cloud("20", roundRobin, rule, templates);
    }

    private EC2Cloud cloud(
            String instanceCap, boolean roundRobin, HotSpareConfigByLabel rule, SlaveTemplate... templates)
            throws Exception {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        EC2Cloud cloud = new EC2Cloud(
                "test-cloud", true, "abc", "us-east-1", null, "ghi", instanceCap, List.of(templates), null, null);
        cloud.setRoundRobinTemplatesByLabel(roundRobin);
        if (rule != null) {
            cloud.setHotSpareConfigsByLabel(List.of(rule));
        }
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static SlaveTemplate template(String description, InstanceType type, int instanceCap, int hotSpareWeight) {
        SlaveTemplate template = new SlaveTemplate(
                "ami-" + description,
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "/tmp/jenkins",
                type.toString(),
                false,
                LABEL,
                Node.Mode.NORMAL,
                description,
                "",
                "",
                "",
                "1",
                "",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "",
                false,
                null,
                null,
                null,
                0,
                0,
                String.valueOf(instanceCap),
                null,
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
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
        template.setHotSpareWeight(hotSpareWeight);
        /*
         * The mocked describe-instances ignores filters, so without this a request would find the
         * instance a previous one launched, treat it as an orphan and reuse it instead of testing
         * the cap of the template it was aimed at.
         */
        template.setAvoidUsingOrphanedNodes(true);
        return template;
    }
}
