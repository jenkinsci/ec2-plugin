package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Label;
import hudson.model.Node;
import hudson.model.User;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;

/**
 * Label rotation and next-template fallback driven purely by {@code provision(Label, int)}, with no
 * hot spare rule configured. Rotating the templates of a label and falling back to the next one are
 * meant to work for ordinary label-driven provisioning, not only for hot spares.
 */
@WithJenkins
class EC2CloudLabelRotationTest {

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
     * With rotation enabled, consecutive requests for the same label use different templates even
     * though the first one could have served both.
     */
    @Test
    void testConsecutiveRequestsRotateAcrossTheTemplatesOfALabel() throws Exception {
        SlaveTemplate first = template("first", FIRST_TYPE, 10);
        SlaveTemplate second = template("second", SECOND_TYPE, 10);
        EC2Cloud cloud = cloud(true, first, second);
        assertThat("no hot spare rule is needed for rotation", cloud.getHotSpareConfigsByLabel(), equalTo(List.of()));

        cloud.provision(Label.get(LABEL), 1);
        cloud.provision(Label.get(LABEL), 1);

        assertThat(awaitInstanceTypes(2), containsInAnyOrder(FIRST_TYPE.toString(), SECOND_TYPE.toString()));
    }

    /**
     * The flag is what changes the order, so with it off the configured order is used for every
     * request, as it was before rotation existed.
     */
    @Test
    void testRotationDisabledKeepsTheConfiguredOrderForEveryRequest() throws Exception {
        SlaveTemplate first = template("first", FIRST_TYPE, 10);
        SlaveTemplate second = template("second", SECOND_TYPE, 10);
        EC2Cloud cloud = cloud(false, first, second);

        cloud.provision(Label.get(LABEL), 1);
        cloud.provision(Label.get(LABEL), 1);

        assertThat(awaitInstanceTypes(2), contains(FIRST_TYPE.toString(), FIRST_TYPE.toString()));
        assertThat(cloud.orderTemplatesForLabel(Label.get(LABEL), List.of(first, second)), contains(first, second));
    }

    /**
     * Saturating the highest weight first sends every request to the heavier template. Under the
     * default proportional rotation the lighter one would have led the third of these four
     * requests.
     */
    @Test
    void testSaturatingHighestWeightSendsEveryRequestToTheHeavierTemplate() throws Exception {
        SlaveTemplate preferred = template("preferred", FIRST_TYPE, 10);
        preferred.setHotSpareWeight(3);
        SlaveTemplate fallback = template("fallback", SECOND_TYPE, 10);
        fallback.setHotSpareWeight(1);
        EC2Cloud cloud = cloud(true, preferred, fallback);
        cloud.setSaturateHighestWeightFirst(true);

        for (int i = 0; i < 4; i++) {
            cloud.provision(Label.get(LABEL), 1);
        }

        assertThat(
                awaitInstanceTypes(4),
                contains(FIRST_TYPE.toString(), FIRST_TYPE.toString(), FIRST_TYPE.toString(), FIRST_TYPE.toString()));
    }

    /**
     * ... and the lighter template is what the label falls back to once the heavier one can no
     * longer supply an instance.
     */
    @Test
    void testSaturatingHighestWeightFallsBackOnceTheHeavierTemplateIsExhausted() throws Exception {
        SlaveTemplate preferred = template("preferred", FIRST_TYPE, 1);
        preferred.setHotSpareWeight(3);
        SlaveTemplate fallback = template("fallback", SECOND_TYPE, 10);
        fallback.setHotSpareWeight(1);
        EC2Cloud cloud = cloud(true, preferred, fallback);
        cloud.setSaturateHighestWeightFirst(true);

        // Synchronous, so the cap is genuinely reached before the label request below.
        cloud.provision(preferred, 1);
        assertThat(cloud.getAvailableCapacity(preferred), equalTo(0));

        cloud.provision(Label.get(LABEL), 1);

        assertThat(awaitInstanceTypes(2), contains(FIRST_TYPE.toString(), SECOND_TYPE.toString()));
    }

    /**
     * A rule for a label none of these templates carries must not disturb their provisioning.
     */
    @Test
    void testRuleForAnUnrelatedLabelDoesNotAffectRotation() throws Exception {
        SlaveTemplate first = template("first", FIRST_TYPE, 10);
        SlaveTemplate second = template("second", SECOND_TYPE, 10);
        EC2Cloud cloud = cloud(true, first, second);
        cloud.setHotSpareConfigsByLabel(List.of(new HotSpareConfigByLabel("windows")));

        cloud.provision(Label.get(LABEL), 1);
        cloud.provision(Label.get(LABEL), 1);

        assertThat(awaitInstanceTypes(2), containsInAnyOrder(FIRST_TYPE.toString(), SECOND_TYPE.toString()));
    }

    /**
     * A template at its instance cap is skipped in favour of the next one in the same request. This
     * is the fallback that commit 05176793 introduced, and reaching the cap must not be mistaken
     * for a capacity shortage in EC2.
     */
    @Test
    void testTemplateAtItsInstanceCapFallsBackWithoutCoolingDown() throws Exception {
        SlaveTemplate first = template("first", FIRST_TYPE, 1);
        SlaveTemplate second = template("second", SECOND_TYPE, 10);
        EC2Cloud cloud = cloud(true, first, second);

        // Synchronous, so the cap is genuinely reached before the label request below.
        cloud.provision(first, 1);
        assertThat(cloud.getAvailableCapacity(first), equalTo(0));

        cloud.provision(Label.get(LABEL), 1);

        assertThat(awaitInstanceTypes(2), contains(FIRST_TYPE.toString(), SECOND_TYPE.toString()));
        assertFalse(
                cloud.getRotation().isTemplateInCooldown(first, 2),
                "an instance cap is a local limit, not a capacity shortage, so it must not demote the template");
    }

    /**
     * A launch that comes back with no instances is a failure like any other: move on to the next
     * template, but do not blame EC2 capacity for it.
     */
    @Test
    void testEmptyLaunchResultFallsBackWithoutCoolingDown() throws Exception {
        Mockito.doReturn(RunInstancesResponse.builder()
                        .instances(Collections.emptyList())
                        .build())
                .when(AmazonEC2FactoryMockImpl.mock)
                .runInstances(Mockito.<RunInstancesRequest>argThat(
                        request -> request != null && FIRST_TYPE.toString().equals(request.instanceTypeAsString())));
        SlaveTemplate first = template("first", FIRST_TYPE, 10);
        SlaveTemplate second = template("second", SECOND_TYPE, 10);
        EC2Cloud cloud = cloud(true, first, second);

        cloud.provision(Label.get(LABEL), 1);

        assertThat(awaitInstanceTypes(1), contains(SECOND_TYPE.toString()));
        assertFalse(
                cloud.getRotation().isTemplateInCooldown(first, 2),
                "only an insufficient-capacity error should demote a template");
    }

    /**
     * A client-side SDK failure carries no AWS error code, so it can only mean "try the next
     * template" &mdash; never "this instance type is exhausted".
     */
    @Test
    void testClientSideSdkFailureFallsBackWithoutCoolingDown() throws Exception {
        Mockito.doThrow(SdkClientException.create("no route to EC2"))
                .when(AmazonEC2FactoryMockImpl.mock)
                .runInstances(Mockito.<RunInstancesRequest>argThat(
                        request -> request != null && FIRST_TYPE.toString().equals(request.instanceTypeAsString())));
        SlaveTemplate first = template("first", FIRST_TYPE, 10);
        SlaveTemplate second = template("second", SECOND_TYPE, 10);
        EC2Cloud cloud = cloud(true, first, second);

        cloud.provision(Label.get(LABEL), 1);

        assertThat(awaitInstanceTypes(1), contains(SECOND_TYPE.toString()));
        assertFalse(
                cloud.getRotation().isTemplateInCooldown(first, 2),
                "only an insufficient-capacity error should demote a template");
    }

    /**
     * The cooldown outlives the request that recorded it, otherwise the next request would run into
     * the same exhausted instance type again.
     */
    @Test
    void testCooldownFromACapacityErrorMovesLaterRequestsToTheOtherTemplate() throws Exception {
        Mockito.doAnswer(invocation -> {
                    throw Ec2Exception.builder()
                            .awsErrorDetails(AwsErrorDetails.builder()
                                    .errorCode("InsufficientInstanceCapacity")
                                    .build())
                            .message("InsufficientInstanceCapacity")
                            .build();
                })
                .when(AmazonEC2FactoryMockImpl.mock)
                .runInstances(Mockito.<RunInstancesRequest>argThat(
                        request -> request != null && FIRST_TYPE.toString().equals(request.instanceTypeAsString())));
        SlaveTemplate first = template("first", FIRST_TYPE, 10);
        SlaveTemplate second = template("second", SECOND_TYPE, 10);
        EC2Cloud cloud = cloud(true, first, second);

        cloud.provision(Label.get(LABEL), 1);
        awaitInstanceTypes(1);

        assertThat(
                "the next request should start from the template that can still deliver",
                cloud.orderTemplatesForLabel(Label.get(LABEL), List.of(first, second)),
                contains(second, first));
    }

    /**
     * A label served by a single template has nowhere to fail over to, so demoting it would only
     * stop it retrying across its own availability zones.
     */
    @Test
    void testSingleTemplateLabelIsNeverCooledDown() throws Exception {
        Mockito.doAnswer(invocation -> {
                    throw Ec2Exception.builder()
                            .awsErrorDetails(AwsErrorDetails.builder()
                                    .errorCode("InsufficientInstanceCapacity")
                                    .build())
                            .message("InsufficientInstanceCapacity")
                            .build();
                })
                .when(AmazonEC2FactoryMockImpl.mock)
                .runInstances(Mockito.any(RunInstancesRequest.class));
        SlaveTemplate only = template("only", FIRST_TYPE, 10);
        EC2Cloud cloud = cloud(true, only);

        cloud.provision(Label.get(LABEL), 1);

        assertFalse(cloud.getRotation().isTemplateInCooldown(only, 1), "a lone template must keep being tried");
    }

    /**
     * A weight is only meaningful once rotation is enabled, so the form says so rather than letting
     * an admin believe a weight is being honoured.
     */
    @Test
    void testHotSpareWeightWarnsWhileRotationIsDisabled() throws Exception {
        SlaveTemplate template = template("first", FIRST_TYPE, 10);
        EC2Cloud cloud = cloud(false, template);
        SlaveTemplate.DescriptorImpl descriptor = r.jenkins.getDescriptorByType(SlaveTemplate.DescriptorImpl.class);

        assertThat(descriptor.doCheckHotSpareWeight("3", cloud).kind, equalTo(FormValidation.Kind.WARNING));
        assertThat(descriptor.doCheckHotSpareWeight("1", cloud).kind, equalTo(FormValidation.Kind.OK));

        cloud.setRoundRobinTemplatesByLabel(true);
        assertThat(descriptor.doCheckHotSpareWeight("3", cloud).kind, equalTo(FormValidation.Kind.OK));
    }

    /**
     * That warning reports how the cloud is configured, so the validation answers nothing at all to
     * someone who may not configure it.
     */
    @Test
    void testHotSpareWeightValidationTellsNonAdministratorsNothing() throws Exception {
        SlaveTemplate template = template("first", FIRST_TYPE, 10);
        EC2Cloud cloud = cloud(false, template);
        SlaveTemplate.DescriptorImpl descriptor = r.jenkins.getDescriptorByType(SlaveTemplate.DescriptorImpl.class);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));

        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertThat(
                    "an administrator gets a warning here",
                    descriptor.doCheckHotSpareWeight("3", cloud).kind,
                    equalTo(FormValidation.Kind.OK));
        }
    }

    /**
     * @return the instance types launched so far, in launch order, once {@code expected} instances
     *     exist. Provisioning finishes on another thread, so the count is what tells us the
     *     requests are done.
     */
    private List<String> awaitInstanceTypes(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            List<String> types = new ArrayList<>(AmazonEC2FactoryMockImpl.instances.stream()
                    .map(instance -> instance.instanceTypeAsString())
                    .collect(Collectors.toList()));
            if (types.size() >= expected) {
                return types;
            }
            Thread.sleep(100);
        }
        return fail("only " + AmazonEC2FactoryMockImpl.instances.size() + " of " + expected
                + " expected instances were launched");
    }

    private EC2Cloud cloud(boolean roundRobinTemplatesByLabel, SlaveTemplate... templates) throws Exception {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        EC2Cloud cloud =
                new EC2Cloud("test-cloud", true, "abc", "us-east-1", null, "ghi", "20", List.of(templates), null, null);
        cloud.setRoundRobinTemplatesByLabel(roundRobinTemplatesByLabel);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static SlaveTemplate template(String description, InstanceType type, int instanceCap) {
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
        /*
         * The mocked describe-instances ignores filters, so without this every request would find
         * the instance the previous one launched, treat it as an orphan and reuse it instead of
         * launching from the template being tested.
         */
        template.setAvoidUsingOrphanedNodes(true);
        return template;
    }
}
