package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The spare target as a load prediction: it climbs while the label cannot keep up, holds while the
 * label is being served, and fades back to the base count once the work stops.
 */
class HotSpareDemandTest {

    private static final String LABEL = "linux";

    private MovableClock clock;
    private EC2Cloud cloud;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        HotSpareDemand.clock = clock;
        HotSpareDemand.reset();
        cloud = new EC2Cloud("test-cloud", true, "abc", "us-east-1", null, "ghi", "20", List.of(), null, null);
    }

    @AfterEach
    void tearDown() {
        HotSpareDemand.clock = Clock.systemDefaultZone();
        HotSpareDemand.reset();
    }

    /**
     * The reported behaviour: a step of 5 has to mean five spares held for as long as the label is
     * busy, not five agents handed out once. A spare running a build is not a spare, so the target
     * stays where it is and the shortfall is reported again.
     */
    @Test
    void testTheTargetIsHeldWhileTheSparesAreBeingConsumed() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        // Five builds arrive with nothing warm to take them.
        assertThat(demand.updateTarget(config, 0, 0, 5, 0), equalTo(5));

        // They are all running now, so the label holds no spares at all and needs five again.
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 0, 0, 5), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(
                "the target must not fade while the label is doing work",
                demand.updateTarget(config, 0, 5, 0, 5),
                equalTo(5));
    }

    /**
     * A label nobody has asked for costs nothing, so the first build to take an executor is what
     * warms it up. Nothing is queued and nothing is waiting at that point: the build in question
     * already has its agent. The spares are for the builds after it.
     */
    @Test
    void testTakingAnExecutorWarmsTheLabelUpFromNothing() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat("nothing is asking for the label yet", demand.updateTarget(config, 0, 0, 0, 0), equalTo(0));

        HotSpareDemand.spareConsumed(cloud, LABEL);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));
    }

    /**
     * The point of reacting to an executor being taken: the pool is one short the moment a build
     * starts, and the target says so, so the replacement is provisioned while that build runs
     * rather than after the next build has already had to wait.
     */
    @Test
    void testEveryExecutorTakenLeavesTheLabelAShortfallToReplace() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, LABEL);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        // Five spares are warm, and builds start taking them one at a time.
        for (int taken = 1; taken <= 4; taken++) {
            clock.advanceSeconds(10);
            HotSpareDemand.spareConsumed(cloud, LABEL);
            int sparesLeft = 5 - taken;
            assertThat(
                    "the target covers the spares taken, so each one is replaced",
                    demand.updateTarget(config, sparesLeft, taken, 0, taken),
                    equalTo(5));
        }
    }

    /**
     * Builds taking the last of the spares means the replacements are not arriving fast enough, so
     * the label climbs a step even though no build has had to queue for it yet.
     */
    @Test
    void testRunningTheSparesDryGrowsTheTarget() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, LABEL);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        clock.advanceMinutes(1);
        consume(5);
        assertThat("nothing warm is left, so hold more of it", demand.updateTarget(config, 0, 5, 0, 6), equalTo(10));

        clock.advanceMinutes(1);
        consume(5);
        assertThat(demand.updateTarget(config, 0, 10, 0, 11), equalTo(15));
    }

    /**
     * One build taking spare after spare is not evidence of five builds' worth of demand, however
     * long it goes on for. Growth stops one step above the work actually in sight, so a saturated
     * label tracks its concurrency plus a step of cover rather than climbing without limit.
     */
    @Test
    void testOneBuildCannotClimbBeyondAStepOfHeadroom() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, LABEL);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        for (int minute = 0; minute < 10; minute++) {
            clock.advanceMinutes(1);
            HotSpareDemand.spareConsumed(cloud, LABEL);
            demand.updateTarget(config, 0, 5, 0, 1);
        }

        assertThat("one busy agent plus a step of cover", demand.getTarget(), equalTo(6));
    }

    /**
     * Five builds of the same job saturating the label is real demand, so the target follows it,
     * but still only as far as that demand plus a step.
     */
    @Test
    void testConcurrentBuildsGrowTheTargetOnlyAsFarAsTheirOwnNumberPlusAStep() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, LABEL);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        for (int minute = 0; minute < 10; minute++) {
            clock.advanceMinutes(1);
            consume(5);
            demand.updateTarget(config, 0, 5, 0, 5);
        }

        assertThat("five busy agents plus a step of cover", demand.getTarget(), equalTo(10));
    }

    /**
     * Spares being taken while others stay warm is exactly what the label is meant to do, so the
     * target holds instead of climbing.
     */
    @Test
    void testSparesBeingTakenWithWarmOnesLeftHoldsTheTarget() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, LABEL);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        clock.advanceMinutes(5);
        HotSpareDemand.spareConsumed(cloud, LABEL);
        assertThat(demand.updateTarget(config, 4, 1, 0, 2), equalTo(5));
        clock.advanceMinutes(5);
        HotSpareDemand.spareConsumed(cloud, LABEL);
        assertThat(demand.updateTarget(config, 4, 1, 0, 3), equalTo(5));
    }

    /**
     * The builds stop, so the label goes back to costing nothing.
     */
    @Test
    void testALabelThatStopsBeingUsedFadesAway() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        config.setIdleTimeoutMinutes(15);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, LABEL);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        // The last build finishes. Nothing has taken an executor since.
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(5));
        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(0));
    }

    /**
     * The demand of a big parallel build is measurable the moment its branches queue up, so the
     * target goes straight to it. Climbing there a step per minute would leave nineteen of twenty
     * branches waiting for the first four minutes, which is the opposite of the point.
     */
    @Test
    void testABigParallelBuildRaisesTheTargetToItsWholeWidthAtOnce() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 20, 0), equalTo(20));
    }

    /**
     * The load, not the step, is what the target follows once the label is in use, whether the work
     * is queued or already running.
     */
    @Test
    void testTheTargetFollowsTheLoadUpAndTheStepIsOnlyItsFloor() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(
                "less work in sight than a step: the step wins", demand.updateTarget(config, 0, 0, 2, 0), equalTo(5));
        assertThat("eight branches waiting", demand.updateTarget(config, 0, 5, 8, 0), equalTo(8));
        assertThat("and now they are running", demand.updateTarget(config, 0, 0, 0, 8), equalTo(8));
        assertThat("a second job joins them", demand.updateTarget(config, 0, 8, 6, 8), equalTo(14));
    }

    /**
     * Following the load covers the work in sight. This covers the work that has not arrived yet: a
     * label whose spares are taken the instant they appear is given one step more than its load,
     * and no more, because a load that is not growing is not evidence of anything beyond that.
     */
    @Test
    void testALabelThatKeepsRunningDryIsGivenAStepMoreThanItsLoad() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        // Five builds running, and every spare that appears is taken straight away.
        consume(5);
        assertThat(demand.updateTarget(config, 0, 0, 0, 5), equalTo(5));

        clock.advanceMinutes(1);
        consume(5);
        assertThat(
                "a step of cover above the five it is running", demand.updateTarget(config, 0, 5, 0, 5), equalTo(10));

        clock.advanceMinutes(1);
        consume(5);
        assertThat("and no further while the load stands still", demand.updateTarget(config, 0, 10, 0, 5), equalTo(10));
    }

    /**
     * Growth is what the instances already on their way cannot cover, so once they can, the target
     * settles instead of chasing the queue.
     */
    @Test
    void testTheTargetSettlesOnceTheInstancesOnTheirWayCoverTheQueue() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 3, 0), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 5, 3, 0), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 5, 0, 3, 0), equalTo(5));
    }

    /**
     * A pass runs every time a build starts, so the same load seen several times over must not read
     * as more load. The measured part of the target is idempotent and the cover is rate-limited.
     */
    @Test
    void testRepeatedChecksWithTheSameLoadDoNotKeepClimbing() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 50, 0), equalTo(50));
        clock.advanceSeconds(5);
        assertThat(demand.updateTarget(config, 0, 50, 50, 0), equalTo(50));
        clock.advanceSeconds(5);
        assertThat(demand.updateTarget(config, 0, 50, 50, 0), equalTo(50));

        clock.advanceMinutes(1);
        assertThat(
                "a minute on with the queue still unserved, so a step of cover",
                demand.updateTarget(config, 0, 0, 50, 0),
                equalTo(55));
    }

    /**
     * With nothing asking for the label, the target gives up a step per idle timeout until the
     * label costs nothing.
     */
    @Test
    void testAQuietLabelFadesToTheBaseCount() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        config.setIdleTimeoutMinutes(15);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 10, 0), equalTo(10));

        // The work stops. The first quiet check only starts the clock.
        assertThat(demand.updateTarget(config, 10, 0, 0, 0), equalTo(10));
        clock.advanceMinutes(14);
        assertThat("not a whole idle timeout yet", demand.updateTarget(config, 10, 0, 0, 0), equalTo(10));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 10, 0, 0, 0), equalTo(5));
        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(0));
        clock.advanceMinutes(15);
        assertThat("the base count is the floor", demand.updateTarget(config, 0, 0, 0, 0), equalTo(0));
    }

    @Test
    void testAQuietLabelFadesNoFurtherThanItsBaseCount() {
        HotSpareConfigByLabel config = rule(2, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(
                "the base count applies before anything is asked of the label",
                demand.updateTarget(config, 0, 0, 0, 0),
                equalTo(2));
        assertThat(demand.updateTarget(config, 0, 0, 4, 0), equalTo(7));

        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 7, 0, 0, 0), equalTo(7));
        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 7, 0, 0, 0), equalTo(2));
        clock.advanceMinutes(30);
        assertThat(demand.updateTarget(config, 2, 0, 0, 0), equalTo(2));
    }

    /**
     * A burst teaches the label a peak that later work does not justify. The target has to fade
     * back towards the work still in sight, otherwise the spares it holds would never be reclaimed:
     * the idle timeout only releases the agents the target has given up on.
     */
    @Test
    void testTheTargetFadesTowardsTheLoadWhileTheLabelStaysBusy() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat("a twenty-branch burst", demand.updateTarget(config, 0, 0, 20, 0), equalTo(20));

        // The burst is over, but two builds keep the label in use. The first pass only starts the clock.
        assertThat(demand.updateTarget(config, 18, 0, 0, 2), equalTo(20));
        clock.advanceMinutes(14);
        assertThat("not a whole idle timeout yet", demand.updateTarget(config, 18, 0, 0, 2), equalTo(20));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 18, 0, 0, 2), equalTo(15));
        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 13, 0, 0, 2), equalTo(10));
        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 8, 0, 0, 2), equalTo(5));
        clock.advanceMinutes(15);
        assertThat(
                "one step is the floor while the label is in use", demand.updateTarget(config, 3, 0, 0, 2), equalTo(5));
    }

    /**
     * Work picking back up during the fade takes the target with it, so a label that is busy again
     * does not carry on giving up spares it is about to need.
     */
    @Test
    void testWorkComingBackDuringTheFadeStopsIt() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 20, 0), equalTo(20));
        assertThat(demand.updateTarget(config, 18, 0, 0, 2), equalTo(20));
        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 18, 0, 0, 2), equalTo(15));

        clock.advanceMinutes(15);
        assertThat("eighteen running builds", demand.updateTarget(config, 0, 0, 0, 18), equalTo(18));
        clock.advanceMinutes(15);
        assertThat("the fade restarts from the new peak", demand.updateTarget(config, 0, 0, 0, 18), equalTo(18));
    }

    @Test
    void testTheCeilingLimitsHowFarTheTargetCanGrow() {
        HotSpareConfigByLabel config = rule(0, 5, 7);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat("forty queued builds, but seven is the limit", demand.updateTarget(config, 0, 0, 40, 0), equalTo(7));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 7, 40, 0), equalTo(7));
    }

    /**
     * A label whose templates are all at their instance caps never keeps up, so growth has to stop
     * at the work in sight. Otherwise the target would climb for as long as the caps held and then
     * try to launch that imagined backlog as soon as capacity appeared.
     */
    @Test
    void testGrowthStopsAtTheWorkInSight() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        for (int minute = 0; minute < 10; minute++) {
            demand.updateTarget(config, 0, 0, 2, 0);
            clock.advanceMinutes(1);
        }

        assertThat("two queued builds plus a step of cover", demand.getTarget(), equalTo(7));
    }

    /**
     * A rule with no step still follows its load; the step only sets how much cover it gets on top,
     * and one is the least that can move at all.
     */
    @Test
    void testARuleWithNoStepStillFollowsItsLoadAndCoversItByOne() {
        HotSpareConfigByLabel config = rule(0, 0, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 10, 0), equalTo(10));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 0, 10, 0), equalTo(11));
    }

    /**
     * Agents that are never idle-terminated have no rate to follow, so the fade falls back to the
     * default idle timeout rather than never happening.
     */
    @Test
    void testALabelWhoseAgentsNeverTimeOutStillFades() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        config.setIdleTimeoutMinutes(0);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 5, 0), equalTo(5));
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(5));
        clock.advanceMinutes(HotSpareConfigByLabel.DEFAULT_IDLE_TIMEOUT_MINUTES);
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(0));
    }

    /**
     * A negative idle timeout is a billing-period timeout, whose magnitude is still the rate the
     * agents disappear at.
     */
    @Test
    void testABillingPeriodIdleTimeoutFadesAtItsOwnRate() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        config.setIdleTimeoutMinutes(-10);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 5, 0), equalTo(5));
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(5));
        clock.advanceMinutes(10);
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(0));
    }

    @Test
    void testEachLabelIsPredictedSeparately() {
        HotSpareConfigByLabel linux = rule(0, 5, null);
        HotSpareConfigByLabel windows = new HotSpareConfigByLabel("windows");
        windows.setScalingFactor(2);

        assertThat(HotSpareDemand.of(cloud, LABEL).updateTarget(linux, 0, 0, 10, 0), equalTo(10));
        assertThat(HotSpareDemand.of(cloud, "windows").updateTarget(windows, 0, 0, 3, 0), equalTo(3));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(10));
    }

    /**
     * The count an admin asked to always be held belongs to the rule, so a label the rule covers
     * without naming starts from nothing. Charging the floor to each covered label would multiply
     * it by however many labels there are.
     */
    @Test
    void testALabelTheRuleOnlyCoversDoesNotClaimTheFloor() {
        HotSpareConfigByLabel config = rule(3, 5, null);

        assertThat(
                "the label the rule names holds the floor",
                HotSpareDemand.of(cloud, LABEL).updateTarget(config, 0, 0, 0, 0),
                equalTo(3));
        assertThat(
                "a label it only covers starts from nothing",
                HotSpareDemand.of(cloud, "covered").updateTarget(config, 0, 0, 0, 0, 0),
                equalTo(0));
    }

    /**
     * Targets are keyed by the label a job asked for, so a controller would otherwise end up
     * holding an entry for every label it has ever seen. A label that has faded to nothing and was
     * not looked at is dropped; one still holding spares is kept whether or not it was.
     */
    @Test
    void testFadedLabelsAreForgottenAndLiveOnesAreKept() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand.of(cloud, LABEL).updateTarget(config, 0, 0, 5, 0);
        HotSpareDemand.of(cloud, "gone").updateTarget(config, 0, 0, 0, 0, 0);
        HotSpareDemand.of(cloud, "quiet").updateTarget(config, 0, 0, 0, 0, 0);

        HotSpareDemand.forgetZeroed(cloud, Set.of("quiet"));

        assertThat(HotSpareDemand.trackedLabels(cloud), equalTo(Set.of(LABEL, "quiet")));
    }

    private void consume(int spares) {
        for (int i = 0; i < spares; i++) {
            HotSpareDemand.spareConsumed(cloud, LABEL);
        }
    }

    private static HotSpareConfigByLabel rule(int baseHotSpares, int step, Integer maxHotSpares) {
        HotSpareConfigByLabel config = new HotSpareConfigByLabel(LABEL);
        config.setBaseHotSpares(baseHotSpares);
        config.setScalingFactor(step);
        config.setMaxHotSpares(maxHotSpares);
        return config;
    }

    private static final class MovableClock extends Clock {

        private long millis = TimeUnit.DAYS.toMillis(1);

        void advanceMinutes(long minutes) {
            millis += TimeUnit.MINUTES.toMillis(minutes);
        }

        void advanceSeconds(long seconds) {
            millis += TimeUnit.SECONDS.toMillis(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
