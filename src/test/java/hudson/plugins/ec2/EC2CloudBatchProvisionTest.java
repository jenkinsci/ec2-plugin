package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.MinimumInstanceChecker;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;

/**
 * A request for several agents has to launch several instances, whichever market it is served from.
 *
 * <p>Every launch the plugin makes is a {@code RunInstances} call carrying the number asked for, a
 * spot template bidding the on-demand price included, so a request for twenty is one call for
 * twenty instances rather than twenty requests for one. An instance type in one subnet is also a
 * single capacity pool that EC2 fills as far as it can rather than refusing what it cannot cover,
 * so what one pool comes up short on has to be asked of the pools behind it while the request is
 * still in hand.
 *
 * <p>Both of those are the difference between a wide build finding its capacity at once and
 * trickling in a pool's worth per provisioning pass, which for a label whose cheapest hardware is
 * spot means a fan-out is served by its more expensive templates, or waits minutes for capacity
 * that was asked for all at once.
 */
@WithJenkins
class EC2CloudBatchProvisionTest {

    private static final String LABEL = "linux";

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        AmazonEC2FactoryMockImpl.mock = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
        HotSpareDemand.reset();
    }

    /**
     * Bidding the on-demand price is the spot configuration with no bid price of its own, and the
     * one the plugin serves through {@code RunInstances}.
     */
    @Test
    void testASpotTemplateBiddingTheOndemandPriceLaunchesEveryInstanceRequested() throws Exception {
        EC2Cloud cloud = cloud(template("spot", 20, spotBiddingTheOndemandPrice()));

        cloud.provision(cloud.getTemplates().get(0), 5);

        awaitInstanceCount(5);
        assertThat(instanceCount(), equalTo(5));
    }

    /**
     * The same through a label request rather than a direct one, since that is the path Jenkins
     * itself provisions on.
     */
    @Test
    void testALabelRequestForSeveralAgentsFillsTheSpotTemplate() throws Exception {
        EC2Cloud cloud = cloud(template("spot", 20, spotBiddingTheOndemandPrice()));

        cloud.provision(Label.get(LABEL), 5);

        awaitInstanceCount(5);
        assertThat(instanceCount(), equalTo(5));
    }

    /**
     * The reported case: a label whose hot spares are held on spot hardware has to reach its
     * target in one pass. Trickling one instance per pass leaves a twenty-branch build waiting
     * minutes for capacity that was asked for all at once.
     */
    @Test
    void testHotSparesOnASpotTemplateReachTheirTargetInOnePass() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(20);
        rule.setScalingFactor(5);
        EC2Cloud cloud = cloud(rule, template("spot", 30, spotBiddingTheOndemandPrice()));

        MinimumInstanceChecker.checkForMinimumInstances();

        awaitInstanceCount(20);
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(20));
        assertThat(agentsByTemplate(), equalTo(Map.of("spot", 20L)));
    }

    /**
     * A cheaper spot template leading the label must take the whole request, or the templates
     * behind it pick up work the admin ranked as more expensive.
     */
    @Test
    void testTheLeadingSpotTemplateTakesTheWholeRequestBeforeTheDearerOne() throws Exception {
        SlaveTemplate spot = template("spot", 30, spotBiddingTheOndemandPrice());
        spot.setHotSpareWeight(10);
        SlaveTemplate demand = template("demand", 30, null);
        demand.setHotSpareWeight(1);
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(20);
        rankedCloud(rule, spot, demand);

        MinimumInstanceChecker.checkForMinimumInstances();

        awaitInstanceCount(20);
        assertThat(agentsByTemplate(), equalTo(Map.of("spot", 20L)));
    }

    /**
     * A template is one spot pool, and EC2 fills a request with however much of it that pool has
     * rather than refusing what it cannot cover in full. The rest of the request has to come from
     * the pools behind it in the same pass, or a wide build is served a pool at a time.
     */
    @Test
    void testARequestIsSatisfiedAcrossSeveralSpotPools() throws Exception {
        // Three pools of the same label, each able to supply part of a request for twelve.
        SlaveTemplate first = template("first", 30, spotBiddingTheOndemandPrice(), InstanceType.M1_LARGE);
        SlaveTemplate second = template("second", 30, spotBiddingTheOndemandPrice(), InstanceType.M1_XLARGE);
        SlaveTemplate third = template("third", 30, spotBiddingTheOndemandPrice(), InstanceType.M2_XLARGE);
        limitRunInstancesFor(InstanceType.M1_LARGE, 3);
        limitRunInstancesFor(InstanceType.M1_XLARGE, 4);
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(12);
        EC2Cloud cloud = cloud(rule, first, second, third);

        MinimumInstanceChecker.checkForMinimumInstances();

        awaitInstanceCount(12);
        assertThat(
                "the pools that could only part-fill the request handed the rest on",
                instanceTypeCounts(),
                equalTo(Map.of(
                        InstanceType.M1_LARGE.toString(),
                        3L,
                        InstanceType.M1_XLARGE.toString(),
                        4L,
                        InstanceType.M2_XLARGE.toString(),
                        5L)));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(12));
    }

    /**
     * A pool with no capacity at all is the other half of that, and it also has to be demoted, so
     * the next pass leads with a pool that can deliver rather than asking the empty one first.
     */
    @Test
    void testAPoolOutOfCapacityHandsTheWholeRequestOnAndIsCooledDown() throws Exception {
        failRunInstancesFor(InstanceType.M1_LARGE);
        SlaveTemplate empty = template("empty", 30, spotBiddingTheOndemandPrice(), InstanceType.M1_LARGE);
        empty.setHotSpareWeight(10);
        SlaveTemplate available = template("available", 30, spotBiddingTheOndemandPrice(), InstanceType.M1_XLARGE);
        available.setHotSpareWeight(1);
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(6);
        EC2Cloud cloud = rankedCloud(rule, empty, available);

        MinimumInstanceChecker.checkForMinimumInstances();

        awaitInstanceCount(6);
        assertThat(agentsByTemplate(), equalTo(Map.of("available", 6L)));
        assertThat(
                "a pool that reported no capacity should be demoted for the next pass",
                cloud.getRotation().isTemplateInCooldown(empty, 2),
                equalTo(true));
    }

    /**
     * On-demand launches take the count through a different branch of the same method, so the
     * plainest case is worth stating on its own: nothing along the way may substitute a count of
     * its own for the one the caller asked for.
     */
    @Test
    void testAnOndemandTemplateLaunchesEveryInstanceRequested() throws Exception {
        EC2Cloud cloud = cloud(template("demand", 20, null));

        cloud.provision(cloud.getTemplates().get(0), 5);

        awaitInstanceCount(5);
        assertThat(instanceCount(), equalTo(5));
    }

    /**
     * On-demand capacity is per instance type and subnet as well, so a request that one template
     * can only part-fill has to be finished off by the templates behind it here too.
     */
    @Test
    void testAnOndemandRequestIsSatisfiedAcrossSeveralTemplates() throws Exception {
        SlaveTemplate first = template("first", 30, null, InstanceType.M1_LARGE);
        SlaveTemplate second = template("second", 30, null, InstanceType.M1_XLARGE);
        SlaveTemplate third = template("third", 30, null, InstanceType.M2_XLARGE);
        limitRunInstancesFor(InstanceType.M1_LARGE, 3);
        limitRunInstancesFor(InstanceType.M1_XLARGE, 4);
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(12);
        cloud(rule, first, second, third);

        MinimumInstanceChecker.checkForMinimumInstances();

        awaitInstanceCount(12);
        assertThat(
                instanceTypeCounts(),
                equalTo(Map.of(
                        InstanceType.M1_LARGE.toString(),
                        3L,
                        InstanceType.M1_XLARGE.toString(),
                        4L,
                        InstanceType.M2_XLARGE.toString(),
                        5L)));
    }

    /**
     * The subnet failover an on-demand launch has of its own rebuilds the request against the next
     * subnet, which has to carry the same count: a request for five that the first subnet cannot
     * take is five instances in the second, not one.
     */
    @Test
    void testSubnetFailoverKeepsTheWholeCountOfAnOndemandRequest() throws Exception {
        AtomicInteger refusals = refuseSubnet("subnet-exhausted");
        EC2Cloud cloud = cloud(
                template("two-subnets", 30, null, InstanceType.M1_LARGE, "subnet-exhausted subnet-with-capacity"));

        cloud.provision(cloud.getTemplates().get(0), 5);

        awaitInstanceCount(5);
        assertThat(instanceCount(), equalTo(5));
        assertThat(
                "the first subnet should have been tried, so this is a failover rather than a lucky first choice",
                refusals.get(),
                greaterThanOrEqualTo(1));
    }

    /**
     * A spot pool is an instance type in one availability zone, so the zones of a template are the
     * first place to look for what a zone came up short on. A request must walk all of them before
     * the label moves on to hardware the admin ranked as more expensive.
     */
    @Test
    void testASpotRequestFallsThroughEveryAvailabilityZone() throws Exception {
        AtomicInteger refusals = new AtomicInteger();
        for (int zone = 1; zone <= 4; zone++) {
            refuseSubnet("subnet-zone-" + zone, refusals);
        }
        EC2Cloud cloud = cloud(template(
                "five-zones",
                30,
                spotBiddingTheOndemandPrice(),
                InstanceType.M1_LARGE,
                "subnet-zone-1 subnet-zone-2 subnet-zone-3 subnet-zone-4 subnet-zone-5"));

        cloud.provision(cloud.getTemplates().get(0), 5);

        awaitInstanceCount(5);
        assertThat(instanceCount(), equalTo(5));
        assertThat(
                "all four exhausted zones should have been tried before the fifth served the request",
                refusals.get(),
                greaterThanOrEqualTo(4));
    }

    /**
     * Zones that can each supply part of a spot request have to be added up, in the same request.
     * Spot capacity is thin per pool by nature, so this is the usual case for a wide build rather
     * than an unusual one.
     */
    @Test
    void testASpotRequestIsSatisfiedAcrossSeveralAvailabilityZones() throws Exception {
        limitSubnet("subnet-zone-1", 3);
        limitSubnet("subnet-zone-2", 3);
        limitSubnet("subnet-zone-3", 3);
        EC2Cloud cloud = cloud(template(
                "three-zones",
                30,
                spotBiddingTheOndemandPrice(),
                InstanceType.M1_LARGE,
                "subnet-zone-1 subnet-zone-2 subnet-zone-3"));

        cloud.provision(cloud.getTemplates().get(0), 9);

        awaitInstanceCount(9);
        assertThat(instanceCount(), equalTo(9));
    }

    /**
     * Zones first, then templates: only once every zone of the cheapest instance type is out of
     * capacity does the label fall back to the next template, and all inside one request.
     */
    @Test
    void testZonesAreExhaustedBeforeTheRequestMovesToTheNextTemplate() throws Exception {
        AtomicInteger refusals = new AtomicInteger();
        refuseSubnet("subnet-zone-1", refusals);
        refuseSubnet("subnet-zone-2", refusals);
        SlaveTemplate cheapest = template(
                "cheapest", 30, spotBiddingTheOndemandPrice(), InstanceType.M1_LARGE, "subnet-zone-1 subnet-zone-2");
        cheapest.setHotSpareWeight(10);
        SlaveTemplate dearer = template("dearer", 30, spotBiddingTheOndemandPrice(), InstanceType.M1_XLARGE);
        dearer.setHotSpareWeight(1);
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(5);
        EC2Cloud cloud = rankedCloud(rule, cheapest, dearer);

        MinimumInstanceChecker.checkForMinimumInstances();

        awaitInstanceCount(5);
        assertThat(agentsByTemplate(), equalTo(Map.of("dearer", 5L)));
        assertThat("both zones of the cheapest template should have been tried", refusals.get(), equalTo(2));
        assertThat(
                "a template with no capacity in any of its zones should be demoted",
                cloud.getRotation().isTemplateInCooldown(cheapest, 2),
                equalTo(true));
    }

    /**
     * The zone walk is not spot-specific, so an on-demand request adds up its zones too.
     */
    @Test
    void testAnOndemandRequestIsSatisfiedAcrossSeveralAvailabilityZones() throws Exception {
        limitSubnet("subnet-zone-1", 2);
        limitSubnet("subnet-zone-2", 2);
        EC2Cloud cloud = cloud(
                template("three-zones", 30, null, InstanceType.M1_LARGE, "subnet-zone-1 subnet-zone-2 subnet-zone-3"));

        cloud.provision(cloud.getTemplates().get(0), 6);

        awaitInstanceCount(6);
        assertThat(instanceCount(), equalTo(6));
    }

    /**
     * Stubs the factory so one instance type launches at most {@code limit} instances per request,
     * which is how EC2 answers a request for more than a pool can supply.
     */
    private void limitRunInstancesFor(InstanceType type, int limit) {
        Mockito.doAnswer(invocation -> {
                    RunInstancesRequest request = invocation.getArgument(0);
                    RunInstancesRequest capped = request.toBuilder()
                            .maxCount(Math.min(request.maxCount(), limit))
                            .build();
                    return AmazonEC2FactoryMockImpl.launchInstances(capped);
                })
                .when(AmazonEC2FactoryMockImpl.mock)
                .runInstances(Mockito.<RunInstancesRequest>argThat(
                        request -> request != null && type.toString().equals(request.instanceTypeAsString())));
    }

    /** Stubs the factory so one instance type has no capacity at all. */
    private void failRunInstancesFor(InstanceType type) {
        Mockito.doAnswer(invocation -> {
                    throw capacityException();
                })
                .when(AmazonEC2FactoryMockImpl.mock)
                .runInstances(Mockito.<RunInstancesRequest>argThat(
                        request -> request != null && type.toString().equals(request.instanceTypeAsString())));
    }

    /**
     * Stubs the factory so one subnet has no capacity.
     *
     * @return how many launches that subnet has refused.
     */
    private AtomicInteger refuseSubnet(String subnetId) {
        return refuseSubnet(subnetId, new AtomicInteger());
    }

    /** As above, counting the refusals of several subnets together. */
    private AtomicInteger refuseSubnet(String subnetId, AtomicInteger refusals) {
        Mockito.doAnswer(invocation -> {
                    refusals.incrementAndGet();
                    throw capacityException();
                })
                .when(AmazonEC2FactoryMockImpl.mock)
                .runInstances(Mockito.<RunInstancesRequest>argThat(request -> inSubnet(request, subnetId)));
        return refusals;
    }

    /** Stubs the factory so one subnet can only part-fill a request, as a thin pool does. */
    private void limitSubnet(String subnetId, int limit) {
        Mockito.doAnswer(invocation -> {
                    RunInstancesRequest request = invocation.getArgument(0);
                    return AmazonEC2FactoryMockImpl.launchInstances(request.toBuilder()
                            .maxCount(Math.min(request.maxCount(), limit))
                            .build());
                })
                .when(AmazonEC2FactoryMockImpl.mock)
                .runInstances(Mockito.<RunInstancesRequest>argThat(request -> inSubnet(request, subnetId)));
    }

    private static boolean inSubnet(RunInstancesRequest request, String subnetId) {
        return request != null && request.networkInterfaces().stream().anyMatch(net -> subnetId.equals(net.subnetId()));
    }

    private static Ec2Exception capacityException() {
        return (Ec2Exception) Ec2Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("InsufficientInstanceCapacity")
                        .build())
                .message("InsufficientInstanceCapacity")
                .build();
    }

    private static Map<String, Long> instanceTypeCounts() {
        return AmazonEC2FactoryMockImpl.instances.stream()
                .collect(Collectors.groupingBy(instance -> instance.instanceTypeAsString(), Collectors.counting()));
    }

    private static SpotConfiguration spotBiddingTheOndemandPrice() {
        SpotConfiguration spotConfig = new SpotConfiguration(false);
        spotConfig.setSpotMaxBidPrice("");
        spotConfig.setFallbackToOndemand(false);
        return spotConfig;
    }

    private static int instanceCount() {
        return AmazonEC2FactoryMockImpl.instances.size();
    }

    private Map<String, Long> agentsByTemplate() {
        return r.jenkins.getNodes().stream()
                .filter(EC2AbstractSlave.class::isInstance)
                .map(EC2AbstractSlave.class::cast)
                .collect(Collectors.groupingBy(node -> node.templateDescription, Collectors.counting()));
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

    private EC2Cloud cloud(SlaveTemplate... templates) throws Exception {
        return cloud(null, templates);
    }

    /**
     * A cloud reading hot spare weights as a ranking, configured before it is added: the rotation
     * asks the cloud whether it is enabled, and adding a cloud saves Jenkins, which runs a hot
     * spare pass of its own before any later setting could be seen.
     */
    private EC2Cloud rankedCloud(HotSpareConfigByLabel rule, SlaveTemplate... templates) throws Exception {
        return cloud(
                rule,
                cloud -> {
                    cloud.setRoundRobinTemplatesByLabel(true);
                    cloud.setSaturateHighestWeightFirst(true);
                },
                templates);
    }

    private EC2Cloud cloud(HotSpareConfigByLabel rule, SlaveTemplate... templates) throws Exception {
        return cloud(rule, cloud -> {}, templates);
    }

    private EC2Cloud cloud(HotSpareConfigByLabel rule, Consumer<EC2Cloud> configure, SlaveTemplate... templates)
            throws Exception {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        // A mutable list: Jenkins refuses to marshal the immutable one List.of returns beyond two
        // elements, and a cloud is saved as soon as it is added.
        EC2Cloud cloud = new EC2Cloud(
                "test-cloud",
                true,
                "abc",
                "us-east-1",
                null,
                "ghi",
                "100",
                new ArrayList<>(List.of(templates)),
                null,
                null);
        if (rule != null) {
            cloud.setHotSpareConfigsByLabel(List.of(rule));
        }
        configure.accept(cloud);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static SlaveTemplate template(String description, int instanceCap, SpotConfiguration spotConfig) {
        return template(description, instanceCap, spotConfig, InstanceType.M1_LARGE);
    }

    private static SlaveTemplate template(
            String description, int instanceCap, SpotConfiguration spotConfig, InstanceType type) {
        return template(description, instanceCap, spotConfig, type, null);
    }

    private static SlaveTemplate template(
            String description, int instanceCap, SpotConfiguration spotConfig, InstanceType type, String subnetId) {
        SlaveTemplate template = new SlaveTemplate(
                "ami-" + description,
                EC2AbstractSlave.TEST_ZONE,
                spotConfig,
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
                subnetId,
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
        // The mocked describe-instances ignores filters, so a request would otherwise find the
        // instances an earlier one launched and adopt them instead of launching.
        template.setAvoidUsingOrphanedNodes(true);
        return template;
    }
}
