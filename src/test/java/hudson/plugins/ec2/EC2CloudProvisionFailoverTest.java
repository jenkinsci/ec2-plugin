package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import java.security.Security;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;

/**
 * Failover between the templates matching one label. The group is homogeneous by definition, so a
 * template that cannot deliver must hand over to the next one inside the same provisioning request
 * instead of costing a whole {@link hudson.slaves.NodeProvisioner} cycle.
 *
 * @see <a href="https://github.com/jenkinsci/ec2-plugin/issues/2033">ec2-plugin issue 2033</a>
 */
@WithJenkins
class EC2CloudProvisionFailoverTest {

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
     * An insufficient-capacity error on the first template must be recovered from within the same
     * call by launching from the second, and the exhausted template is demoted for a while.
     */
    @Test
    @Issue("2033")
    void testCapacityErrorFailsOverToNextTemplateAndCoolsDownTheFirst() throws Exception {
        failRunInstancesFor(FIRST_TYPE, () -> capacityException("InsufficientInstanceCapacity"));
        SlaveTemplate first = template("first", FIRST_TYPE);
        SlaveTemplate second = template("second", SECOND_TYPE);
        EC2Cloud cloud = cloud(true, first, second);

        assertFalse(cloud.provision(Label.get(LABEL), 1).isEmpty(), "a node should have been planned");

        awaitInstanceOfType(SECOND_TYPE);
        assertThat(
                "the exhausted template should have been demoted in the rotation",
                cloud.getRotation().isTemplateInCooldown(first, 2),
                equalTo(true));
    }

    /**
     * The fallback is not gated on the rotation flag: it only takes effect after a template has
     * already failed, so it applies to the default configured order too. The cooldown, which does
     * reorder later requests, stays off.
     */
    @Test
    @Issue("2033")
    void testCapacityErrorFailsOverWithRotationDisabledButRecordsNoCooldown() throws Exception {
        failRunInstancesFor(FIRST_TYPE, () -> capacityException("InsufficientInstanceCapacity"));
        SlaveTemplate first = template("first", FIRST_TYPE);
        SlaveTemplate second = template("second", SECOND_TYPE);
        EC2Cloud cloud = cloud(false, first, second);

        assertFalse(cloud.provision(Label.get(LABEL), 1).isEmpty(), "a node should have been planned");

        awaitInstanceOfType(SECOND_TYPE);
        assertFalse(
                cloud.getRotation().isTemplateInCooldown(first, 2),
                "no cooldown should be recorded while rotation is disabled");
    }

    /**
     * With the default configuration the exhausted template is tried first on every cycle, since
     * nothing demotes it. Each cycle must still end with an instance from the second template
     * rather than looping on the first one.
     */
    @Test
    @Issue("2033")
    void testEveryRequestStillDeliversWhileTheFirstTemplateKeepsFailing() throws Exception {
        failRunInstancesFor(FIRST_TYPE, () -> capacityException("InsufficientInstanceCapacity"));
        SlaveTemplate first = template("first", FIRST_TYPE);
        SlaveTemplate second = template("second", SECOND_TYPE);
        EC2Cloud cloud = cloud(false, first, second);

        cloud.provision(Label.get(LABEL), 1);
        awaitInstanceCount(1);
        cloud.provision(Label.get(LABEL), 1);
        awaitInstanceCount(2);

        assertThat(
                AmazonEC2FactoryMockImpl.instances.stream()
                        .map(instance -> instance.instanceTypeAsString())
                        .collect(Collectors.toList()),
                equalTo(List.of(SECOND_TYPE.toString(), SECOND_TYPE.toString())));
        assertThat(
                "the configured order is unchanged, so the exhausted template leads again",
                cloud.orderTemplatesForLabel(Label.get(LABEL), List.of(first, second)),
                equalTo(List.of(first, second)));
    }

    /**
     * A failure that is not about capacity also falls over to the next template, but must not put
     * the first one into a capacity cooldown.
     */
    @Test
    @Issue("2033")
    void testNonCapacityErrorFailsOverWithoutCooldown() throws Exception {
        failRunInstancesFor(FIRST_TYPE, () -> capacityException("UnauthorizedOperation"));
        SlaveTemplate first = template("first", FIRST_TYPE);
        SlaveTemplate second = template("second", SECOND_TYPE);
        EC2Cloud cloud = cloud(true, first, second);

        assertFalse(cloud.provision(Label.get(LABEL), 1).isEmpty(), "a node should have been planned");

        awaitInstanceOfType(SECOND_TYPE);
        assertFalse(
                cloud.getRotation().isTemplateInCooldown(first, 2), "only capacity errors should demote a template");
    }

    /*
     * Narrows the factory's default runInstances behaviour to fail for one instance type only. The
     * later, more specific stub wins for that type while every other request keeps launching.
     */
    private void failRunInstancesFor(InstanceType type, Supplier<RuntimeException> failure) {
        Mockito.doAnswer(invocation -> {
                    throw failure.get();
                })
                .when(AmazonEC2FactoryMockImpl.mock)
                .runInstances(Mockito.<RunInstancesRequest>argThat(
                        request -> request != null && type.toString().equals(request.instanceTypeAsString())));
    }

    private static Ec2Exception capacityException(String errorCode) {
        return (Ec2Exception) Ec2Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
                .message(errorCode)
                .build();
    }

    private void awaitInstanceCount(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            if (AmazonEC2FactoryMockImpl.instances.size() >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(
                false,
                "only " + AmazonEC2FactoryMockImpl.instances.size() + " of " + expected + " instances were launched");
    }

    private void awaitInstanceOfType(InstanceType type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            if (AmazonEC2FactoryMockImpl.instances.stream()
                    .anyMatch(instance -> type.toString().equals(instance.instanceTypeAsString()))) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(false, "no instance of type " + type + " was launched");
    }

    private EC2Cloud cloud(boolean roundRobinTemplatesByLabel, SlaveTemplate... templates) throws Exception {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        EC2Cloud cloud =
                new EC2Cloud("test-cloud", true, "abc", "us-east-1", null, "ghi", "20", List.of(templates), null, null);
        cloud.setRoundRobinTemplatesByLabel(roundRobinTemplatesByLabel);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static SlaveTemplate template(String description, InstanceType type) {
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
                "10",
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
        /*
         * The mocked describe-instances ignores filters, so without this a later request would find
         * the instance an earlier one launched, treat it as an orphan and reuse it instead of
         * exercising the failover.
         */
        template.setAvoidUsingOrphanedNodes(true);
        return template;
    }
}
