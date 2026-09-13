package hudson.plugins.ec2.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import hudson.ExtensionList;
import hudson.model.Executor;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.plugins.ec2.ConnectionStrategy;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2RetentionStrategy;
import hudson.plugins.ec2.EbsEncryptRootVolume;
import hudson.plugins.ec2.HotSpareConfigByLabel;
import hudson.plugins.ec2.HotSpareDemand;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.Tenancy;
import hudson.slaves.NodeProvisioner;
import java.security.Security;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import software.amazon.awssdk.services.ec2.model.InstanceType;

/**
 * Hot spare scaling driven by a label rule rather than by a single template.
 */
@WithJenkins
class LabelHotSpareCheckerTest {

    private static final String LABEL = "linux";

    /**
     * No test here wants a build to actually start: what is being measured is the spares a queue
     * warms up, and a branch being picked up by an agent moves it out of the queue and the agent
     * out of the pool, mid-count. Vetoing every assignment keeps the queue still.
     */
    @TestExtension
    public static class KeepEveryBuildQueued extends QueueTaskDispatcher {

        @Override
        public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
            return new CauseOfBlockage() {
                @Override
                public String getShortDescription() {
                    return "held in the queue by LabelHotSpareCheckerTest";
                }
            };
        }
    }

    private JenkinsRule r;

    private MovableClock clock;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        // The mock client and its instance list are static, so a fresh one keeps the instances of
        // one test from counting against the caps of the next.
        AmazonEC2FactoryMockImpl.mock = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
        clock = new MovableClock();
        HotSpareDemand.clock = clock;
        HotSpareDemand.reset();
        // Jenkins provisions for a queued build on its own account, off a timer, and these tests now
        // react to the same queue. Its agents would be indistinguishable from spares in the counts
        // below, so the tests that use a real build measure only what the hot spare pass launched.
        ExtensionList<NodeProvisioner.Strategy> strategies = r.jenkins.getExtensionList(NodeProvisioner.Strategy.class);
        strategies.removeAll(new ArrayList<>(strategies));
    }

    @AfterEach
    void tearDown() {
        HotSpareDemand.clock = Clock.systemDefaultZone();
        MinimumInstanceChecker.clock = Clock.systemDefaultZone();
        HotSpareDemand.reset();
    }

    /**
     * The point of a hot spare is to exist before the work does, so a base count must provision
     * with an empty queue.
     */
    @Test
    void testBaseHotSparesAreProvisionedWithAnEmptyQueue() throws Exception {
        HotSpareConfigByLabel rule = rule(2, 0, null);
        cloud(rule, template("only", 10));

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(2));
    }

    /**
     * The spares already held are subtracted, so repeated ticks converge instead of provisioning
     * the same shortfall again.
     */
    @Test
    void testRepeatedChecksDoNotProvisionTheSameShortfallTwice() throws Exception {
        HotSpareConfigByLabel rule = rule(2, 0, null);
        cloud(rule, template("only", 10));

        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(2));
    }

    /**
     * A template only allowed to hold minimum instances during a time range must not be warmed
     * outside it. Its schedule is how an admin pays for capacity only when it is wanted, and a
     * label rule that ignored it would quietly put the template back on the clock 24x7.
     */
    @Test
    void testATemplateOutsideItsScheduleHoldsNoSpares() throws Exception {
        HotSpareConfigByLabel rule = rule(2, 0, null);
        SlaveTemplate template = template("only", 10);
        template.setMinimumNumberOfInstancesTimeRangeConfig(window("11:00", "15:00"));
        // Before the cloud is saved: saving one runs a hot spare pass, which would read the
        // schedules against whatever the wall clock happens to say.
        atLocalTime(18, 0);
        cloud(rule, template);

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(0));
    }

    /**
     * Inside the window the same rule provisions as usual, so the schedule gates the spares rather
     * than disabling them.
     */
    @Test
    void testATemplateInsideItsScheduleHoldsItsSpares() throws Exception {
        HotSpareConfigByLabel rule = rule(2, 0, null);
        SlaveTemplate template = template("only", 10);
        template.setMinimumNumberOfInstancesTimeRangeConfig(window("11:00", "15:00"));
        atLocalTime(12, 0);
        cloud(rule, template);

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(2));
    }

    /**
     * The schedule belongs to the template, not to the label, so a label served by several
     * templates keeps its spares on whichever of them is on duty. This is the arrangement the
     * feature is meant to replace - a template per shift, offset so one is always awake - working
     * as a single pool.
     */
    @Test
    void testSparesMoveToTheTemplateWhoseScheduleIsOpen() throws Exception {
        HotSpareConfigByLabel rule = rule(2, 0, null);
        SlaveTemplate dayShift = template("day", 10);
        dayShift.setMinimumNumberOfInstancesTimeRangeConfig(window("08:00", "18:00"));
        SlaveTemplate nightShift = template("night", 10);
        nightShift.setMinimumNumberOfInstancesTimeRangeConfig(window("18:00", "08:00"));
        atLocalTime(22, 0);
        cloud(rule, dayShift, nightShift);

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(2));
        assertThat(agentsByTemplate(), equalTo(Map.of("night", 2L)));
    }

    /**
     * A template carrying several labels can be covered by a rule for each of them. The rules are
     * counted against the same agents rather than added together, so the label wanting the most
     * decides how many the template holds and the other one adds nothing.
     */
    @Test
    void testOverlappingRulesDoNotAddUpOnASharedTemplate() throws Exception {
        // The smaller rule first, since that is also the order the idle timeout is resolved in.
        EC2Cloud cloud = cloud(List.of(rule("bar", 1), rule("foo", 4)), template("shared", 10, "foo bar"));

        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat("four for foo, of which bar's one is a subset", countAgents(), equalTo(4));
        assertThat(HotSpareDemand.of(cloud, "foo").getTarget(), equalTo(4));
        assertThat(HotSpareDemand.of(cloud, "bar").getTarget(), equalTo(1));
    }

    /**
     * The other half of that: overlapping is not the same as sharing everything, so a rule provisions
     * only from the templates carrying its own label.
     */
    @Test
    void testARuleOnlyProvisionsFromTheTemplatesItCovers() throws Exception {
        cloud(
                List.of(rule("foo", 2), rule("baz", 3)),
                template("shared", 10, "foo bar"),
                template("elsewhere", 10, "baz"));

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(agentsByTemplate(), equalTo(Map.of("shared", 2L, "elsewhere", 3L)));
    }

    /**
     * Work in sight belongs to the label that asked for it. A build wanting one label of a shared
     * template says nothing about the other, even though the same machines serve both: what the
     * build asked for is the only evidence of what it would have accepted.
     */
    @Test
    void testWorkForOneLabelIsNotWorkInSightForAnother() throws Exception {
        cloud(List.of(rule("bar", 0), rule("foo", 0)), template("shared", 10, "foo bar"));

        r.jenkins.setQuietPeriod(0);
        FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(Label.get("bar"));
        project.scheduleBuild2(0);
        Queue.getInstance().maintain();
        waitForABuildableItem();

        assertThat(MinimumInstanceChecker.countQueueItemsRequesting(Label.get("bar")), equalTo(1));
        assertThat(
                "a build asking for bar is not work in sight for foo",
                MinimumInstanceChecker.countQueueItemsRequesting(Label.get("foo")),
                equalTo(0));
    }

    /**
     * The reported defect: a rule covering both architectures had a run of x86 pull request builds
     * warming arm64 instances that none of those builds could ever have used. A rule says how the
     * labels it covers should behave; it does not make them one pool.
     */
    @Test
    void testABuildForOneLabelDoesNotWarmTheOtherHardwareOfTheRule() throws Exception {
        EC2Cloud cloud = cloud(
                rule("x86_64_medium_pr || arm64_medium_pr", 0, 5),
                template("x86", 10, "x86_64_medium_pr"),
                template("arm", 10, "arm64_medium_pr"));

        queueBuildFor("x86_64_medium_pr");
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(agentsByTemplate(), equalTo(Map.of("x86", 5L)));
        assertThat(
                "arm64 was never asked for",
                HotSpareDemand.of(cloud, "arm64_medium_pr").getTarget(),
                equalTo(0));
    }

    /**
     * The same, for the label that made the defect visible in practice: a rule grouping small
     * agents with the generator label used to have generator builds warm both architectures of
     * small agent.
     */
    @Test
    void testALabelSharingARuleWithOthersScalesOnItsOwn() throws Exception {
        EC2Cloud cloud = cloud(
                rule("x86_64_small || arm64_small || jervis_generator", 0, 2),
                template("x86", 10, "x86_64_small jervis_generator"),
                template("arm", 10, "arm64_small"));

        queueBuildFor("jervis_generator");
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(agentsByTemplate(), equalTo(Map.of("x86", 2L)));
        assertThat(HotSpareDemand.of(cloud, "jervis_generator").getTarget(), equalTo(2));
        assertThat(HotSpareDemand.of(cloud, "arm64_small").getTarget(), equalTo(0));
    }

    /**
     * Labels scaling apart is not the same as their spares being separate machines. Where one
     * template serves both labels, a warm agent counts for both of them, so two labels wanting two
     * spares each are served by two agents rather than four.
     */
    @Test
    void testLabelsServedByOneTemplateShareItsSpares() throws Exception {
        EC2Cloud cloud = cloud(rule("foo || bar", 0, 2), template("shared", 10, "foo bar"));

        queueBuildFor("foo");
        queueBuildFor("bar");
        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat("one set of spares, serving both labels", countAgents(), equalTo(2));
        assertThat(HotSpareDemand.of(cloud, "foo").getTarget(), equalTo(2));
        assertThat(HotSpareDemand.of(cloud, "bar").getTarget(), equalTo(2));
    }

    /**
     * A job asking for a compound expression is a label in its own right: the rule covering the
     * templates that carry both atoms governs it, and it is warmed separately from either atom on
     * its own.
     */
    @Test
    void testACompoundExpressionIsTrackedUnderTheRuleThatCoversIt() throws Exception {
        EC2Cloud cloud =
                cloud(rule("foo || bar", 0, 2), template("both", 10, "foo bar"), template("foo-only", 10, "foo"));

        queueBuildFor("foo && bar");
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(
                "only the template carrying both labels can serve it", agentsByTemplate(), equalTo(Map.of("both", 2L)));
        assertThat(HotSpareDemand.of(cloud, "foo&&bar").getTarget(), equalTo(2));
        assertThat(
                "the atoms are separate demand", HotSpareDemand.of(cloud, "foo").getTarget(), equalTo(0));
    }

    /**
     * A build takes a spare on an agent that serves several labels, and only the label it asked for
     * is short of one. The others were not using that agent.
     */
    @Test
    void testTakingAnExecutorOnlyWarmsTheLabelTheBuildAskedFor() throws Exception {
        SlaveTemplate template = template("shared", 20, "foo bar");
        EC2Cloud cloud = cloud(rule("foo || bar", 0, 2), template);

        cloud.provision(template, 1);
        EC2Computer computer = onlyAgent();
        retentionStrategyOf(computer).taskAccepted(new Executor(computer, 0), taskAskingFor("foo"));

        waitForAtLeastAgents(2);
        assertThat(HotSpareDemand.of(cloud, "foo").getTarget(), equalTo(2));
        assertThat(HotSpareDemand.of(cloud, "bar").getTarget(), equalTo(0));
    }

    /**
     * The count an admin asked to always be held belongs to the rule, not to each label it covers,
     * so a rule over four labels with a floor of two holds two agents rather than eight.
     */
    @Test
    void testTheFloorIsHeldOncePerRuleRatherThanPerLabel() throws Exception {
        cloud(rule("foo || bar", 2, 0), template("first", 10, "foo"), template("second", 10, "bar"));

        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(2));
    }

    /**
     * Targets are keyed by whatever label a job asked for, so a controller would otherwise
     * accumulate one for every label it has ever seen. A label that has faded to nothing is
     * forgotten; the labels the rules name stay, because that is where the floors live.
     */
    @Test
    void testALabelThatHasFadedToNothingIsForgotten() throws Exception {
        HotSpareConfigByLabel rule = rule("foo || bar", 0, 2);
        rule.setIdleTimeoutMinutes(15);
        EC2Cloud cloud = cloud(rule, template("shared", 10, "foo bar"));

        queueBuildFor("foo");
        MinimumInstanceChecker.checkForMinimumInstances();
        assertThat(HotSpareDemand.trackedLabels(cloud), hasItem("foo"));

        cancelAllQueuedBuilds();
        removeAllAgents();
        // One pass to see the label go quiet, and one an idle timeout later to fade it to nothing.
        clock.advanceMinutes(16);
        MinimumInstanceChecker.checkForMinimumInstances();
        clock.advanceMinutes(16);
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(HotSpareDemand.trackedLabels(cloud), equalTo(Set.of("foo || bar")));
    }

    /**
     * The ceiling applies to the label group as a whole, not to each template in it.
     */
    @Test
    void testMaxHotSparesCapsTheGroupTotal() throws Exception {
        HotSpareConfigByLabel rule = rule(5, 0, 3);
        cloud(rule, template("first", 10), template("second", 10));

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(3));
    }

    /**
     * Spares are counted across every template carrying the label, so a group that already holds
     * enough is left alone even though no single template does.
     */
    @Test
    void testSparesAreCountedAcrossTheWholeLabelGroup() throws Exception {
        HotSpareConfigByLabel rule = rule(4, 0, null);
        cloud(rule, template("first", 1), template("second", 3));

        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(4));
        assertThat(
                "each template should stay within its own instance cap",
                agentsByTemplate(),
                equalTo(Map.of("first", 1L, "second", 3L)));
    }

    /**
     * A rule for a label none of the templates carries provisions nothing.
     */
    @Test
    void testRuleForAnUnknownLabelProvisionsNothing() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel("no-such-label");
        rule.setBaseHotSpares(3);
        cloud(rule, template("only", 10));

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(0));
    }

    /**
     * The reported defect: a step of 5 used to hand out five agents once and then let the label
     * drain, because the target was derived from the queue and the queue empties as soon as the
     * builds start. The target has to survive the queue emptying, and the spares consumed have to
     * be replaced.
     */
    @Test
    void testSparesAreReplacedAsTheyAreConsumed() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 5, null);
        SlaveTemplate template = template("only", 20);
        // Replacements have to be new instances here, or the mock hands back the ones the departed
        // agents left behind and the test cannot tell provisioning from adoption.
        template.setAvoidUsingOrphanedNodes(true);
        EC2Cloud cloud = cloud(rule, template);
        // A burst the label could not keep up with, which is what raises the target to five.
        HotSpareDemand.of(cloud, LABEL).updateTarget(rule, 0, 0, 5, 0);

        MinimumInstanceChecker.checkForMinimumInstances();
        assertThat(countAgents(), equalTo(5));

        // The builds take all five, so the label holds nothing warm again.
        removeAllAgents();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat("the five spares should have been replaced", countAgents(), equalTo(5));
        assertThat("five more instances launched", AmazonEC2FactoryMockImpl.instances.size(), equalTo(10));
    }

    /**
     * Nothing has asked for the label for longer than the idle timeout, so it should stop paying
     * for spares altogether rather than holding the level its last burst asked for.
     */
    @Test
    void testAQuietLabelStopsReplacingSpares() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 5, null);
        rule.setIdleTimeoutMinutes(15);
        EC2Cloud cloud = cloud(rule, template("only", 20));
        HotSpareDemand.of(cloud, LABEL).updateTarget(rule, 0, 0, 5, 0);

        MinimumInstanceChecker.checkForMinimumInstances();
        assertThat(countAgents(), equalTo(5));

        removeAllAgents();
        clock.advanceMinutes(16);
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat("the prediction should have faded to nothing", countAgents(), equalTo(0));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(0));
    }

    /**
     * The label with nothing warm is the case the spares exist for, and it is the one case the
     * agents cannot report themselves: with no capacity, no executor is taken. Queueing a build has
     * to be enough on its own, or the first build of the day waits for the periodic sweep before
     * anything is even asked for.
     */
    @Test
    void testQueueingABuildProvisionsSparesWithoutWaitingForASweep() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 3, null);
        EC2Cloud cloud = cloud(rule, template("only", 20));

        r.jenkins.setQuietPeriod(0);
        FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(Label.get(LABEL));
        project.scheduleBuild2(0);

        // Only what Jenkins does by itself: the queue is maintained, which makes the build buildable.
        Queue.getInstance().maintain();

        waitForAtLeastAgents(3);
        assertThat(countAgents(), equalTo(3));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(3));
    }

    /**
     * The other half of the reported defect: even with the right target, waiting for the next
     * periodic pass leaves the pool a spare short for up to a minute after every build starts. A
     * build taking an executor is what has to start the replacement, so the spare is booting while
     * that build runs rather than after the build behind it has already had to wait.
     */
    @Test
    void testTakingAnExecutorStartsTheNextSpareStraightAway() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 2, null);
        SlaveTemplate template = template("only", 20);
        template.setAvoidUsingOrphanedNodes(true);
        EC2Cloud cloud = cloud(rule, template);

        MinimumInstanceChecker.checkForMinimumInstances();
        assertThat("a label nobody is using costs nothing", countAgents(), equalTo(0));

        // A build lands on the agent Jenkins raised for it and takes its executor.
        cloud.provision(template, 1);
        EC2Computer computer = onlyAgent();
        int launchedBefore = AmazonEC2FactoryMockImpl.instances.size();
        retentionStrategyOf(computer).taskAccepted(new Executor(computer, 0), taskAskingFor(LABEL));

        /*
         * Nothing else has happened: no queued build, no periodic pass, and the clock has not moved.
         * The agent above counts as one of the two the label now wants, so one more is launched.
         */
        waitForAtLeastAgents(2);
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(2));
        assertThat(
                "one launch, not a whole pool", AmazonEC2FactoryMockImpl.instances.size(), equalTo(launchedBefore + 1));
    }

    /**
     * Hot spare passes run off the thread that triggered them, so their agents are not there the
     * instant a build is queued or an executor is taken.
     *
     * <p>Waits for the number of spares asked for, and no longer: a label serving builds ends up
     * with more agents than that, because the agents running those builds are not spares.
     */
    private static void waitForABuildableItem() throws Exception {
        waitForBuildableItems(1);
    }

    private static void waitForBuildableItems(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (Queue.getInstance().getBuildableItems().size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            Queue.getInstance().maintain();
        }
        assertThat(
                "the build should have reached the queue by now",
                Queue.getInstance().getBuildableItems().size(),
                greaterThanOrEqualTo(expected));
    }

    /** Queues a build that will only run on the given label, and waits for it to be buildable. */
    private void queueBuildFor(String label) throws Exception {
        r.jenkins.setQuietPeriod(0);
        int queuedBefore = Queue.getInstance().getBuildableItems().size();
        FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(Label.get(label));
        project.scheduleBuild2(0);
        Queue.getInstance().maintain();
        waitForBuildableItems(queuedBefore + 1);
    }

    private static void cancelAllQueuedBuilds() {
        for (Queue.Item item : Queue.getInstance().getItems()) {
            Queue.getInstance().cancel(item);
        }
        Queue.getInstance().maintain();
    }

    private static void waitForAtLeastAgents(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (countAgents() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat("the hot spare pass should have provisioned by now", countAgents(), greaterThanOrEqualTo(expected));
    }

    private static EC2Computer onlyAgent() {
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no EC2 agent was provisioned"));
    }

    /** A build that would only run on the given label, for the paths that read what it asked for. */
    private Queue.Task taskAskingFor(String label) throws Exception {
        FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(Label.get(label));
        return project;
    }

    private static EC2RetentionStrategy retentionStrategyOf(EC2Computer computer) {
        EC2AbstractSlave node = computer.getNode();
        assertThat(node, notNullValue());
        return (EC2RetentionStrategy) node.getRetentionStrategy();
    }

    /**
     * A build fanning out to twenty parallel branches puts twenty tasks in the queue at once, and
     * that is measured demand rather than a guess. The label goes to twenty on the spot instead of
     * climbing there five at a time over four minutes with nineteen branches waiting.
     */
    @Test
    void testAWideParallelBuildWarmsTheLabelToItsWholeWidth() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 5, null);
        EC2Cloud cloud = cloud(rule, template("only", 30));

        r.jenkins.setQuietPeriod(0);
        for (int branch = 0; branch < 20; branch++) {
            FreeStyleProject project = r.createFreeStyleProject();
            project.setAssignedLabel(Label.get(LABEL));
            project.scheduleBuild2(0);
        }
        Queue.getInstance().maintain();

        waitForAtLeastAgents(20);
        assertThat(countAgents(), equalTo(20));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(20));
    }

    /**
     * The same through a real {@code parallel} step rather than twenty separate jobs, because that
     * is how a build of this shape actually reaches the queue: as twenty placeholder tasks of one
     * build, each asking for the label its {@code node} step named. The width has to be read off
     * those tasks, or the fan-out that most needs capacity is the one the label cannot see.
     */
    @Test
    void testARealParallelBuildWarmsTheLabelToItsWholeWidth() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 5, null);
        EC2Cloud cloud = cloud(rule, template("only", 30));

        r.jenkins.setQuietPeriod(0);
        parallelBuildAcross(20, LABEL);

        waitForAtLeastAgents(20);
        assertThat(countAgents(), equalTo(20));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(20));
    }

    /**
     * Warm capacity already in hand is part of the width, not additional to it. Five idle spares
     * and a build fanning out to twenty branches is fifteen instances to launch: the label ends up
     * holding twenty, having paid for fifteen.
     */
    @Test
    void testSparesAlreadyHeldCountTowardsTheWidthOfAParallelBuild() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 5, null);
        EC2Cloud cloud = cloud(rule, template("only", 30));

        // A label that has already learned it wants five, and is holding them.
        HotSpareDemand.of(cloud, LABEL).updateTarget(rule, 0, 0, 5, 0);
        MinimumInstanceChecker.checkForMinimumInstances();
        assertThat(countAgents(), equalTo(5));
        int launchedForTheFirstFive = AmazonEC2FactoryMockImpl.instances.size();

        r.jenkins.setQuietPeriod(0);
        parallelBuildAcross(20, LABEL);
        waitForAtLeastAgents(20);

        assertThat(countAgents(), equalTo(20));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(20));
        assertThat(
                "the five already warm were not provisioned again",
                AmazonEC2FactoryMockImpl.instances.size(),
                equalTo(launchedForTheFirstFive + 15));
    }

    /**
     * A parallel build of one label says nothing about another label of the same rule, however wide
     * it is: twenty x86 branches are twenty x86 agents and no arm64 ones.
     */
    @Test
    void testAParallelBuildOnlyWarmsTheLabelItsBranchesAskedFor() throws Exception {
        EC2Cloud cloud = cloud(
                rule("x86_64_medium_pr || arm64_medium_pr", 0, 5),
                template("x86", 30, "x86_64_medium_pr"),
                template("arm", 30, "arm64_medium_pr"));

        r.jenkins.setQuietPeriod(0);
        parallelBuildAcross(20, "x86_64_medium_pr");

        waitForAtLeastAgents(20);
        assertThat(agentsByTemplate(), equalTo(Map.of("x86", 20L)));
        assertThat(HotSpareDemand.of(cloud, "x86_64_medium_pr").getTarget(), equalTo(20));
        assertThat(HotSpareDemand.of(cloud, "arm64_medium_pr").getTarget(), equalTo(0));
    }

    /**
     * Starts a build that fans out to the given number of branches, each asking for the label, and
     * waits until every branch is in the queue waiting for an agent.
     */
    private void parallelBuildAcross(int branches, String label) throws Exception {
        StringJoiner script = new StringJoiner(", ", "parallel ", "");
        for (int branch = 0; branch < branches; branch++) {
            script.add("branch" + branch + ": { node('" + label + "') { } }");
        }
        WorkflowJob job = r.createProject(WorkflowJob.class, "fan-out-" + label.hashCode());
        job.setDefinition(new CpsFlowDefinition(script.toString(), true));
        job.scheduleBuild2(0);
        waitForBuildableItems(branches);
    }

    private void removeAllAgents() throws Exception {
        for (Node node : new ArrayList<>(r.jenkins.getNodes())) {
            r.jenkins.removeNode(node);
        }
    }

    /**
     * A window on every day of the week, so only the time of day decides whether the template is
     * allowed to be holding instances.
     */
    private static MinimumNumberOfInstancesTimeRangeConfig window(String from, String to) {
        MinimumNumberOfInstancesTimeRangeConfig window = new MinimumNumberOfInstancesTimeRangeConfig();
        window.setMinimumNoInstancesActiveTimeRangeFrom(from);
        window.setMinimumNoInstancesActiveTimeRangeTo(to);
        window.setMonday(true);
        window.setTuesday(true);
        window.setWednesday(true);
        window.setThursday(true);
        window.setFriday(true);
        window.setSaturday(true);
        window.setSunday(true);
        return window;
    }

    /** Fixes the wall clock the schedules are read against. */
    private static void atLocalTime(int hour, int minute) {
        LocalDateTime when = LocalDateTime.of(2019, Month.SEPTEMBER, 24, hour, minute); // A Tuesday
        MinimumInstanceChecker.clock =
                Clock.fixed(when.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    }

    /** A rule for another label, with a fixed count and no scaling of its own. */
    private static HotSpareConfigByLabel rule(String label, int baseHotSpares) {
        return rule(label, baseHotSpares, 0);
    }

    private static HotSpareConfigByLabel rule(String label, int baseHotSpares, int scalingFactor) {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(label);
        rule.setBaseHotSpares(baseHotSpares);
        rule.setScalingFactor(scalingFactor);
        return rule;
    }

    private static HotSpareConfigByLabel rule(int baseHotSpares, int scalingFactor, Integer maxHotSpares) {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(baseHotSpares);
        rule.setScalingFactor(scalingFactor);
        rule.setMaxHotSpares(maxHotSpares);
        return rule;
    }

    private static int countAgents() {
        return (int) Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .count();
    }

    private static Map<String, Long> agentsByTemplate() {
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .map(EC2Computer::getNode)
                .filter(node -> node != null)
                .collect(Collectors.groupingBy(node -> node.templateDescription, Collectors.counting()));
    }

    private EC2Cloud cloud(HotSpareConfigByLabel rule, SlaveTemplate... templates) throws Exception {
        return cloud(List.of(rule), templates);
    }

    private EC2Cloud cloud(List<HotSpareConfigByLabel> rules, SlaveTemplate... templates) throws Exception {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        // A cloud-wide cap high enough to leave the template caps as the only limit in play.
        EC2Cloud cloud = new EC2Cloud(
                "test-cloud", true, "abc", "us-east-1", null, "ghi", "100", List.of(templates), null, null);
        cloud.setHotSpareConfigsByLabel(rules);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static final class MovableClock extends Clock {

        private long millis = TimeUnit.DAYS.toMillis(1);

        void advanceMinutes(long minutes) {
            millis += TimeUnit.MINUTES.toMillis(minutes);
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

    private static SlaveTemplate template(String description, int instanceCap) {
        return template(description, instanceCap, LABEL);
    }

    private static SlaveTemplate template(String description, int instanceCap, String labels) {
        return new SlaveTemplate(
                "ami-" + description,
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "/tmp/jenkins",
                InstanceType.M1_LARGE.toString(),
                false,
                labels,
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
    }
}
