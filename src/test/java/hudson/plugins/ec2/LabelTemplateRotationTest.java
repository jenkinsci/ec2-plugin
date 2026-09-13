package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

import hudson.model.Node;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.InstanceType;

class LabelTemplateRotationTest {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();
    private static final String LABEL = "linux";

    private boolean enabled = true;

    private boolean saturateHighestWeightFirst = false;

    private LabelTemplateRotation rotation() {
        return new LabelTemplateRotation(() -> enabled, () -> saturateHighestWeightFirst);
    }

    /**
     * Equal weights give plain round-robin: over one full cycle every template leads exactly once.
     */
    @Test
    void testEqualWeightsLeadOnceEachPerCycle() {
        LabelTemplateRotation rotation = rotation();
        List<SlaveTemplate> group = List.of(template("a", 1), template("b", 1), template("c", 1));

        List<String> heads = heads(rotation, group, 3);

        assertThat(heads, contains("a", "b", "c"));
    }

    /**
     * The full order is returned every time, so a caller can fall back to the remaining templates
     * within the same provisioning request.
     */
    @Test
    void testOrderContainsWholeGroupRotatedAroundTheHead() {
        LabelTemplateRotation rotation = rotation();
        List<SlaveTemplate> group = List.of(template("a", 1), template("b", 1), template("c", 1));

        assertThat(descriptions(rotation.order(LABEL, group)), contains("a", "b", "c"));
        assertThat(descriptions(rotation.order(LABEL, group)), contains("b", "c", "a"));
        assertThat(descriptions(rotation.order(LABEL, group)), contains("c", "a", "b"));
    }

    /**
     * By default a weight is a share of the requests: weights 3 and 1 pick the heavier template
     * three times as often, and the picks interleave instead of bursting, which is what makes the
     * fallback to the lighter template fast.
     */
    @Test
    void testWeightedRotationInterleavesPicks() {
        LabelTemplateRotation rotation = rotation();
        List<SlaveTemplate> group = List.of(template("spot", 3), template("ondemand", 1));

        List<String> heads = heads(rotation, group, 8);

        assertThat(heads, contains("spot", "spot", "ondemand", "spot", "spot", "spot", "ondemand", "spot"));
        assertThat(
                "the heavier template should lead six of eight requests",
                heads.stream().filter("spot"::equals).count(),
                equalTo(6L));
    }

    /**
     * Saturating the highest weight first turns the same weights into a ranking: the heavier
     * template leads every request rather than three out of four, and the lighter one stays behind
     * it as the fallback for the request.
     */
    @Test
    void testSaturatingHighestWeightKeepsTheHeaviestLeading() {
        saturateHighestWeightFirst = true;
        LabelTemplateRotation rotation = rotation();
        List<SlaveTemplate> group = List.of(template("spot", 3), template("ondemand", 1));

        for (int i = 0; i < 8; i++) {
            assertThat(descriptions(rotation.order(LABEL, group)), contains("spot", "ondemand"));
        }
    }

    /**
     * Templates sharing a weight are one band and still take turns, so a label spreads over the
     * instance types an admin considers equivalent instead of concentrating on the first one.
     */
    @Test
    void testSaturatingHighestWeightRotatesWithinABand() {
        saturateHighestWeightFirst = true;
        LabelTemplateRotation rotation = rotation();
        List<SlaveTemplate> group = List.of(template("spot a", 3), template("spot b", 3), template("ondemand", 1));

        assertThat(descriptions(rotation.order(LABEL, group)), contains("spot a", "spot b", "ondemand"));
        assertThat(descriptions(rotation.order(LABEL, group)), contains("spot b", "spot a", "ondemand"));
        assertThat(descriptions(rotation.order(LABEL, group)), contains("spot a", "spot b", "ondemand"));
    }

    /**
     * Bands are ordered by weight rather than by configuration, so the fallback within a request
     * always walks down the ranking.
     */
    @Test
    void testSaturatingHighestWeightOrdersEveryBandByWeight() {
        saturateHighestWeightFirst = true;
        LabelTemplateRotation rotation = rotation();
        List<SlaveTemplate> group = List.of(template("light", 1), template("heavy", 5), template("middle", 3));

        assertThat(descriptions(rotation.order(LABEL, group)), contains("heavy", "middle", "light"));
    }

    /**
     * The next band down leads only once every template above it is cooling down, which is what
     * makes an exhausted instance type hand the label over rather than share it.
     */
    @Test
    void testSaturatingHighestWeightFallsToTheNextBandOnceTheTopIsCoolingDown() {
        saturateHighestWeightFirst = true;
        LabelTemplateRotation rotation = rotation();
        SlaveTemplate spotA = template("spot a", 3);
        SlaveTemplate spotB = template("spot b", 3);
        SlaveTemplate onDemand = template("ondemand", 1);
        List<SlaveTemplate> group = List.of(spotA, spotB, onDemand);

        rotation.markTemplateUnavailable(spotA, group.size());
        assertThat(descriptions(rotation.order(LABEL, group)), contains("spot b", "ondemand", "spot a"));

        rotation.markTemplateUnavailable(spotB, group.size());
        assertThat(descriptions(rotation.order(LABEL, group)), contains("ondemand", "spot a", "spot b"));
    }

    /**
     * A weight of zero remains a last resort under the ranking as well.
     */
    @Test
    void testSaturatingHighestWeightKeepsZeroWeightLast() {
        saturateHighestWeightFirst = true;
        LabelTemplateRotation rotation = rotation();
        List<SlaveTemplate> group = List.of(template("lastResort", 0), template("preferred", 1));

        assertThat(descriptions(rotation.order(LABEL, group)), contains("preferred", "lastResort"));
    }

    /**
     * A weight of zero excludes a template from the rotation while any other template is available.
     */
    @Test
    void testZeroWeightNeverLeadsWhileOthersAreAvailable() {
        LabelTemplateRotation rotation = rotation();
        List<SlaveTemplate> group = List.of(template("preferred", 1), template("lastResort", 0));

        List<String> heads = heads(rotation, group, 5);

        assertThat(heads, contains("preferred", "preferred", "preferred", "preferred", "preferred"));
    }

    /**
     * ... but it is used once every other template in the group is cooling down, rather than
     * leaving the label with nothing to provision from.
     */
    @Test
    void testZeroWeightIsUsedWhenEveryOtherTemplateIsCoolingDown() {
        LabelTemplateRotation rotation = rotation();
        SlaveTemplate preferred = template("preferred", 1);
        SlaveTemplate lastResort = template("lastResort", 0);
        List<SlaveTemplate> group = List.of(preferred, lastResort);

        rotation.markTemplateUnavailable(preferred, group.size());

        assertThat(descriptions(rotation.order(LABEL, group)), contains("lastResort", "preferred"));
    }

    /**
     * A template that reported insufficient capacity is demoted to the end of the order, and
     * returns to its normal position once the cooldown elapses.
     */
    @Test
    void testCapacityCooldownDemotesTemplateUntilItExpires() {
        Instant now = Instant.now();
        LabelTemplateRotation rotation = rotation();
        rotation.setClock(Clock.fixed(now, ZONE_ID));
        SlaveTemplate a = template("a", 1);
        SlaveTemplate b = template("b", 1);
        List<SlaveTemplate> group = List.of(a, b);

        rotation.markTemplateUnavailable(a, group.size());
        assertThat(rotation.isTemplateInCooldown(a, group.size()), equalTo(true));
        assertThat(descriptions(rotation.order(LABEL, group)), contains("b", "a"));

        rotation.setClock(Clock.fixed(
                now.plus(Duration.ofMillis(LabelTemplateRotation.DEFAULT_TEMPLATE_CAPACITY_COOLDOWN_MILLIS + 1)),
                ZONE_ID));
        assertFalse(rotation.isTemplateInCooldown(a, group.size()), "the cooldown should have expired");
        assertThat(descriptions(rotation.order(LABEL, group)), contains("a", "b"));
    }

    /**
     * When every template is cooling down the full group is still returned, so a launch is
     * attempted rather than refused.
     */
    @Test
    void testAllTemplatesCoolingDownStillReturnsTheWholeGroup() {
        LabelTemplateRotation rotation = rotation();
        SlaveTemplate a = template("a", 1);
        SlaveTemplate b = template("b", 1);
        List<SlaveTemplate> group = List.of(a, b);

        rotation.markTemplateUnavailable(a, group.size());
        rotation.markTemplateUnavailable(b, group.size());

        assertThat(descriptions(rotation.order(LABEL, group)), contains("a", "b"));
        assertThat(descriptions(rotation.order(LABEL, group)), contains("b", "a"));
    }

    /**
     * A label served by a single template has nothing to fail over to, so the cooldown must not
     * engage: the template keeps retrying across its own availability zones.
     */
    @Test
    void testSingleTemplateGroupIsNeverCooledDown() {
        LabelTemplateRotation rotation = rotation();
        SlaveTemplate only = template("only", 1);
        List<SlaveTemplate> group = List.of(only);

        rotation.markTemplateUnavailable(only, group.size());
        rotation.markTemplateUnavailable(only, group.size());

        assertFalse(rotation.isTemplateInCooldown(only, group.size()), "a lone template must stay in rotation");
        assertThat(descriptions(rotation.order(LABEL, group)), contains("only"));
    }

    /**
     * With the cloud-level flag off, the configured order is returned untouched and no cooldown is
     * recorded, so the default behaviour is fully deterministic.
     */
    @Test
    void testRotationDisabledPreservesConfiguredOrder() {
        enabled = false;
        LabelTemplateRotation rotation = rotation();
        SlaveTemplate a = template("a", 1);
        SlaveTemplate b = template("b", 3);
        SlaveTemplate c = template("c", 1);
        List<SlaveTemplate> group = List.of(a, b, c);

        rotation.markTemplateUnavailable(b, group.size());

        assertFalse(rotation.isTemplateInCooldown(b, group.size()), "no cooldown should apply when rotation is off");
        for (int i = 0; i < 3; i++) {
            assertThat(descriptions(rotation.order(LABEL, group)), contains("a", "b", "c"));
        }
    }

    /**
     * Rotation state is per label, so a request for one label does not advance another's cursor.
     */
    @Test
    void testRotationStateIsPerLabel() {
        LabelTemplateRotation rotation = rotation();
        List<SlaveTemplate> group = List.of(template("a", 1), template("b", 1));

        assertThat(descriptions(rotation.order("linux", group)), contains("a", "b"));
        assertThat(descriptions(rotation.order("windows", group)), contains("a", "b"));
        assertThat(descriptions(rotation.order("linux", group)), contains("b", "a"));
    }

    /**
     * A template saved before the weight existed must read as weight 1, not 0, which would exclude
     * it from the rotation on upgrade.
     */
    @Test
    void testHotSpareWeightDefaultsToOneAndRejectsNegatives() {
        SlaveTemplate template = template("unset");
        assertThat(template.getHotSpareWeight(), equalTo(1));

        template.setHotSpareWeight(0);
        assertThat(template.getHotSpareWeight(), equalTo(0));

        template.setHotSpareWeight(-5);
        assertThat(template.getHotSpareWeight(), equalTo(0));
    }

    private List<String> heads(LabelTemplateRotation rotation, List<SlaveTemplate> group, int requests) {
        List<String> heads = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            heads.add(rotation.order(LABEL, group).get(0).getDescription());
        }
        return heads;
    }

    private static List<String> descriptions(List<SlaveTemplate> templates) {
        return templates.stream().map(SlaveTemplate::getDescription).collect(Collectors.toList());
    }

    private static SlaveTemplate template(String description, int hotSpareWeight) {
        SlaveTemplate template = template(description);
        template.setHotSpareWeight(hotSpareWeight);
        return template;
    }

    private static SlaveTemplate template(String description) {
        return new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "/tmp/jenkins",
                InstanceType.M1_LARGE.toString(),
                false,
                LABEL + " windows",
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
                "subnet-123",
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
    }
}
