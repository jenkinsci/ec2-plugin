package hudson.plugins.ec2.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.init.Terminator;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.queue.WorkUnit;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.HotSpareConfigByLabel;
import hudson.plugins.ec2.HotSpareDemand;
import hudson.plugins.ec2.SlaveTemplate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Restricted(NoExternalUse.class)
public class MinimumInstanceChecker {

    private static final Logger LOGGER = Logger.getLogger(MinimumInstanceChecker.class.getName());

    /**
     * Executor for deferred minimum-instance checks. Heavy provisioning (EC2 API, cloud.provision)
     * runs here so callers (taskAccepted, EC2SlaveMonitor) return immediately.
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MinimumInstanceChecker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Set while a check is queued but has not started reading the state of Jenkins yet.
     */
    private static final AtomicBoolean CHECK_QUEUED = new AtomicBoolean();

    /**
     * Schedules a minimum-instance check to run asynchronously. Use this instead of
     * {@link #checkForMinimumInstances()} when the caller must return immediately (e.g. taskAccepted).
     *
     * <p>Requests that arrive while one is already waiting are dropped: the queued check has not
     * looked at Jenkins yet, so it will see everything they would have asked about. Without this, a
     * burst of builds starting at once would queue one redundant pass per build behind the single
     * checker thread.
     */
    public static void scheduleCheck() {
        if (!CHECK_QUEUED.compareAndSet(false, true)) {
            return;
        }
        try {
            EXECUTOR.execute(() -> {
                CHECK_QUEUED.set(false);
                checkForMinimumInstances();
            });
        } catch (RuntimeException e) {
            // Jenkins is shutting down. Leaving the flag set would silence every later request.
            CHECK_QUEUED.set(false);
            throw e;
        }
    }

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Needs to be overridden from tests")
    public static Clock clock = Clock.systemDefaultZone();

    private static Stream<EC2Computer> agentsForTemplate(@NonNull SlaveTemplate agentTemplate) {
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .filter(computer -> {
                    SlaveTemplate computerTemplate = computer.getSlaveTemplate();
                    return computerTemplate != null
                            && Objects.equals(computerTemplate.description, agentTemplate.description);
                });
    }

    public static int countCurrentNumberOfAgents(@NonNull SlaveTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate).count();
    }

    private static Stream<EC2Computer> idleAgents(@NonNull SlaveTemplate agentTemplate) {
        return agentsForTemplate(agentTemplate).filter(Computer::isIdle);
    }

    public static int countCurrentNumberOfSpareAgents(@NonNull SlaveTemplate agentTemplate) {
        return (int) idleAgents(agentTemplate).filter(Computer::isOnline).count();
    }

    /**
     * @return the number of agents of this template that exist but cannot take work yet. Counted
     *     from attachment rather than from the launcher starting, for the reason given on
     *     {@link #countCurrentNumberOfProvisioningAgentsForLabel}.
     */
    public static int countCurrentNumberOfProvisioningAgents(@NonNull SlaveTemplate agentTemplate) {
        return (int) idleAgents(agentTemplate)
                .filter(Computer::isOffline)
                .filter(computer -> !computer.isTemporarilyOffline())
                .count();
    }

    /*
        Get the number of queued builds that match an AMI (agentTemplate)
    */
    public static int countQueueItemsForAgentTemplate(@NonNull SlaveTemplate agentTemplate) {
        return (int) Queue.getInstance().getBuildableItems().stream()
                .map((Queue.Item item) -> item.getAssignedLabel())
                .filter(Objects::nonNull)
                .filter((Label label) -> label.matches(agentTemplate.getLabelSet()))
                .count();
    }

    /**
     * Agents of a cloud, whichever of its templates they came from.
     */
    private static Stream<EC2Computer> agentsOf(@NonNull EC2Cloud cloud) {
        Set<String> descriptions = cloud.getTemplates().stream()
                .map(template -> template.description)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .filter(computer -> {
                    SlaveTemplate computerTemplate = computer.getSlaveTemplate();
                    return computerTemplate != null && descriptions.contains(computerTemplate.description);
                });
    }

    /**
     * Agents that could serve a label: a hot spare target is held by the label work asked for, and
     * any template of the cloud carrying that label can hold it, not just the one an earlier agent
     * happened to come from.
     */
    private static Stream<EC2Computer> agentsForLabel(@NonNull EC2Cloud cloud, @NonNull Label label) {
        Set<String> descriptions = cloud.getTemplates(label).stream()
                .map(template -> template.description)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return agentsOf(cloud).filter(computer -> {
            SlaveTemplate computerTemplate = computer.getSlaveTemplate();
            return computerTemplate != null && descriptions.contains(computerTemplate.description);
        });
    }

    public static int countCurrentNumberOfSpareAgentsForLabel(@NonNull EC2Cloud cloud, @NonNull Label label) {
        return (int) agentsForLabel(cloud, label)
                .filter(MinimumInstanceChecker::isSpare)
                .count();
    }

    /**
     * @return whether an agent is warm capacity the label can count on right now. An agent that has
     *     drained its maximum number of uses is idle and online but stops accepting tasks, so
     *     counting it would let a pool of agents that can never run anything satisfy the target. An
     *     agent whose template is outside its schedule does not count either, so the label warms up
     *     on a template that is on duty and lets this one go at its idle timeout.
     */
    private static boolean isSpare(@NonNull EC2Computer computer) {
        SlaveTemplate template = computer.getSlaveTemplate();
        return computer.isIdle()
                && computer.isOnline()
                && computer.isAcceptingTasks()
                && !computer.isTemporarilyOffline()
                && (template == null || keepsWarmInstancesNow(template));
    }

    /**
     * @return whether a template may be holding instances nothing has asked for at this moment.
     *     <i>Only apply minimum number of instances during specific time range</i> is how an admin
     *     pays for warm capacity only when it is worth having, so a hot spare rule honours it
     *     instead of quietly putting the template back on the clock around the clock. The schedule
     *     stays a property of the template, which is what lets one label be served by a template
     *     per shift.
     */
    public static boolean keepsWarmInstancesNow(@NonNull SlaveTemplate template) {
        return minimumInstancesActive(template.getMinimumNumberOfInstancesTimeRangeConfig());
    }

    /**
     * Whether the hot spare prediction for a label is still counting on this agent, which is the
     * question the idle timeout has to ask before reclaiming it. Terminating a spare the target
     * wants pays for the same capacity twice - once for the instance thrown away, again for the one
     * the next pass launches in its place - and leaves a build waiting for the boot in between.
     *
     * <p>The spares are ranked by how long each has been idle and the freshest {@code target} of
     * them are kept, so several agents asking at once agree on which are the extras and exactly the
     * surplus is released. Releasing the longest-idle first keeps the behaviour of an idle timeout:
     * the agent that has been sitting unused the longest is the one that goes.
     *
     * <p>The agent being asked about is included whether or not the counted set has caught up with
     * it, because the comparison is meaningless if the pool it is ranked against excludes it.
     *
     * <p>An agent can serve several labels, each with a target of its own, so the question is asked
     * of every label it could take work for: it is surplus only once none of them is counting on
     * it.
     *
     * @return true when some label wants this agent kept warm, false when it is surplus to all of
     *     them or is not warm capacity at all.
     */
    public static boolean isSpareStillWanted(@NonNull EC2Cloud cloud, @NonNull EC2Computer computer) {
        if (!isSpare(computer)) {
            return false;
        }
        return HotSpareDemand.trackedLabels(cloud).stream()
                .anyMatch(labelName -> isSpareStillWantedFor(cloud, labelName, computer));
    }

    private static boolean isSpareStillWantedFor(
            @NonNull EC2Cloud cloud, @NonNull String labelName, @NonNull EC2Computer computer) {
        if (labelName.isBlank()) {
            return false;
        }
        int target = HotSpareDemand.of(cloud, labelName).getTarget();
        if (target <= 0) {
            return false;
        }

        Label label = Label.get(labelName);
        SlaveTemplate template = computer.getSlaveTemplate();
        if (label == null || template == null || !label.matches(template.getLabelSet())) {
            return false;
        }

        List<EC2Computer> spares = agentsForLabel(cloud, label)
                .filter(MinimumInstanceChecker::isSpare)
                .collect(Collectors.toCollection(ArrayList::new));
        if (spares.stream().noneMatch(spare -> spare == computer)) {
            spares.add(computer);
        }
        if (spares.size() <= target) {
            return true;
        }
        spares.sort(Comparator.comparingLong(Computer::getIdleStartMilliseconds).thenComparing(Computer::getName));
        return spares.subList(spares.size() - target, spares.size()).stream().anyMatch(spare -> spare == computer);
    }

    /**
     * @return the number of agents of the label group that exist but cannot take work yet.
     *     <p>An agent is counted from the moment it is attached rather than from the moment its
     *     launcher starts, because {@link Computer#isConnecting()} is false for the gap in between
     *     and two passes falling either side of that gap would launch the same shortfall twice.
     *     Passes are triggered by builds queueing and starting, so they do arrive in quick
     *     succession. An agent that never comes up is dealt with by its grace period.
     */
    public static int countCurrentNumberOfProvisioningAgentsForLabel(@NonNull EC2Cloud cloud, @NonNull Label label) {
        return (int) agentsForLabel(cloud, label)
                .filter(Computer::isIdle)
                .filter(Computer::isOffline)
                .filter(computer -> !computer.isTemporarilyOffline())
                .count();
    }

    /**
     * @return the number of agents running a build that asked for this label. They are not spares,
     *     which is why a spare taken by a build is replaced, but they do say the label is in use.
     *     <p>What the build asked for is the measure, not what the agent happens to be able to run:
     *     an agent serving a branch build on a template that also carries the pull request label
     *     says nothing about how much pull request capacity to keep warm.
     */
    public static int countAgentsRunningWorkFor(@NonNull EC2Cloud cloud, @NonNull Label label) {
        return (int) agentsForLabel(cloud, label)
                .filter(computer -> !computer.isIdle())
                .filter(computer ->
                        computer.getExecutors().stream().anyMatch(executor -> isAssignedTo(executor, label)))
                .count();
    }

    /**
     * @return the number of buildable queue items waiting for exactly this label. An item waiting
     *     for a different label is another label's demand, even when one template could serve both.
     */
    public static int countQueueItemsRequesting(@NonNull Label label) {
        return (int) Queue.getInstance().getBuildableItems().stream()
                .map((Queue.Item item) -> item.getAssignedLabel())
                .filter(assigned -> isSameLabel(label, assigned))
                .count();
    }

    /**
     * Checks all EC2 cloud templates and provisions agents to meet minimum instance requirements.
     * Synchronized to prevent concurrent provisioning decisions that could lead to over-provisioning
     * when multiple agents accept tasks simultaneously.
     *
     * @see <a href="https://issues.jenkins.io/browse/JENKINS-76171">JENKINS-76171</a>
     */
    public static synchronized void checkForMinimumInstances() {
        Jenkins jenkins = Jenkins.get();

        // Early exit if nothing asks for instances to be kept warm
        boolean hasMinimumRequirements = jenkins.clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .anyMatch(cloud -> !cloud.getHotSpareConfigsByLabel().isEmpty()
                        || cloud.getTemplates().stream()
                                .anyMatch(template -> template.getMinimumNumberOfInstances() > 0
                                        || template.getMinimumNumberOfSpareInstances() > 0));

        if (!hasMinimumRequirements) {
            // Neither minimum instances nor label hot spares are configured - exit immediately
            return;
        }

        jenkins.clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .forEach(cloud -> cloud.getTemplates().forEach(agentTemplate -> {
                    // Minimum instances now have a time range, check to see
                    // if we are within that time range and return early if not.
                    if (!minimumInstancesActive(agentTemplate.getMinimumNumberOfInstancesTimeRangeConfig())) {
                        return;
                    }
                    int requiredMinAgents = agentTemplate.getMinimumNumberOfInstances();
                    int requiredMinSpareAgents = agentTemplate.getMinimumNumberOfSpareInstances();
                    int currentNumberOfAgentsForTemplate = countCurrentNumberOfAgents(agentTemplate);
                    int currentNumberOfSpareAgentsForTemplate = countCurrentNumberOfSpareAgents(agentTemplate);
                    int currentNumberOfProvisioningAgentsForTemplate =
                            countCurrentNumberOfProvisioningAgents(agentTemplate);
                    int currentBuildsWaitingForTemplate = countQueueItemsForAgentTemplate(agentTemplate);
                    int provisionForMinAgents = 0;
                    int provisionForMinSpareAgents = 0;

                    // Check if we need to provision any agents because we
                    // don't have the minimum number of agents
                    provisionForMinAgents = requiredMinAgents - currentNumberOfAgentsForTemplate;
                    if (provisionForMinAgents < 0) {
                        provisionForMinAgents = 0;
                    }

                    // Check if we need to provision any agents because we
                    // don't have the minimum number of spare agents.
                    // Don't double provision if minAgents and minSpareAgents are set.
                    if (requiredMinSpareAgents > 0) {
                        provisionForMinSpareAgents = (requiredMinSpareAgents + currentBuildsWaitingForTemplate)
                                - (currentNumberOfSpareAgentsForTemplate
                                        + provisionForMinAgents
                                        + currentNumberOfProvisioningAgentsForTemplate);
                        if (provisionForMinSpareAgents < 0) {
                            provisionForMinSpareAgents = 0;
                        }
                    }

                    int numberToProvision = provisionForMinAgents + provisionForMinSpareAgents;

                    if (numberToProvision > 0 || requiredMinAgents > 0 || requiredMinSpareAgents > 0) {
                        LOGGER.log(
                                Level.FINE,
                                "MinimumInstanceChecker for template {0}: toProvision={1}",
                                new Object[] {agentTemplate.description, numberToProvision});
                    }

                    if (numberToProvision > 0) {
                        cloud.provision(agentTemplate, numberToProvision);
                    }
                }));

        jenkins.clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .forEach(MinimumInstanceChecker::checkForLabelHotSpares);
    }

    /**
     * Tops up the hot spares of a cloud, one label at a time. Runs inside
     * {@link #checkForMinimumInstances()} rather than from a monitor of its own so all provisioning
     * decisions stay behind the same lock (JENKINS-76171).
     *
     * <p>A rule sets the policy; the label a job asked for is what scales. A rule covering
     * {@code x86_64_medium || arm64_medium} says how both should behave, but each keeps a target of
     * its own, so a run of x86 builds warms x86 hardware and leaves the arm64 pool cold. Only the
     * count an admin asked to be held at all times belongs to the rule as a whole, and it is held
     * by the label the rule names.
     *
     * <p>A rule owns the spare count for its label, so a template's
     * {@link SlaveTemplate#getMinimumNumberOfSpareInstances()} no longer applies to the templates
     * the rule covers. A template's time range for keeping instances is another matter and is still
     * obeyed: outside it the template holds no spares and the label warms up on whichever of its
     * templates is on duty, so a label can be served by a template per shift.
     *
     * <p>Only idle agents count towards the target, so a spare taken by a build is a shortfall to be
     * replaced, which is what keeps a label warm for the whole of a busy period instead of draining
     * with the first few builds. Taking an executor schedules this pass
     * ({@link hudson.plugins.ec2.EC2RetentionStrategy#taskAccepted}), so the replacement launches
     * while the build that took the spare is still starting. Instances already on their way are
     * subtracted as well, otherwise every pass would re-provision the same shortfall while the
     * previous batch is still booting. {@link HotSpareDemand} decides what the target itself
     * should be.
     */
    private static void checkForLabelHotSpares(@NonNull EC2Cloud cloud) {
        Map<String, HotSpareConfigByLabel> tracked = trackedLabels(cloud);
        Set<String> namedByRules = new LinkedHashSet<>();
        for (Map.Entry<String, HotSpareConfigByLabel> entry : tracked.entrySet()) {
            String labelName = entry.getKey();
            HotSpareConfigByLabel config = entry.getValue();
            Label label = Label.get(labelName);
            if (label == null) {
                continue;
            }
            Collection<SlaveTemplate> matching = cloud.getTemplates(label);
            if (matching.isEmpty()) {
                continue;
            }

            int currentSpares = countCurrentNumberOfSpareAgentsForLabel(cloud, label);
            int currentProvisioning = countCurrentNumberOfProvisioningAgentsForLabel(cloud, label);
            int busyAgents = countAgentsRunningWorkFor(cloud, label);
            int queuedBuilds = countQueueItemsRequesting(label);
            // The rule's floor is held by the label the rule names, so the labels it covers scale
            // from nothing rather than each claiming a floor of their own.
            boolean namedByRule = labelName.equals(config.getLabel());
            int base = namedByRule ? config.getBaseHotSpares() : 0;
            if (namedByRule) {
                namedByRules.add(labelName);
            }

            int target = HotSpareDemand.of(cloud, labelName)
                    .updateTarget(config, base, currentSpares, currentProvisioning, queuedBuilds, busyAgents);
            int toLaunch = target - (currentSpares + currentProvisioning);

            LOGGER.log(
                    Level.FINE,
                    "Hot spares for label {0}: target={1}, spare={2}, provisioning={3}, busy={4}, queued={5}, toLaunch={6}",
                    new Object[] {
                        labelName, target, currentSpares, currentProvisioning, busyAgents, queuedBuilds, toLaunch
                    });

            if (toLaunch > 0) {
                provisionAcrossLabelGroup(cloud, label, matching, toLaunch);
            }
        }
        HotSpareDemand.forgetZeroed(cloud, namedByRules);
    }

    /**
     * The labels of a cloud that have a hot spare target this pass, each with the rule that governs
     * it. A label is tracked because a rule names it, because work is waiting for it, because work
     * is running on it, or because it still holds a target from earlier and has to fade rather than
     * drop.
     *
     * <p>A label a rule only covers is governed by the first rule in configuration order that
     * covers a template able to serve it, which is the same order the rest of the plugin resolves a
     * label's settings in.
     *
     * <p>Insertion ordered so a pass is reproducible, with the labels the rules name first: those
     * carry the floors, and holding them first means the labels underneath find them already warm.
     */
    private static Map<String, HotSpareConfigByLabel> trackedLabels(@NonNull EC2Cloud cloud) {
        Map<String, HotSpareConfigByLabel> tracked = new LinkedHashMap<>();
        for (HotSpareConfigByLabel config : cloud.getHotSpareConfigsByLabel()) {
            String labelName = config.getLabel();
            if (labelName != null && !labelName.isBlank()) {
                tracked.putIfAbsent(labelName, config);
            }
        }
        Stream.of(
                        HotSpareDemand.trackedLabels(cloud).stream(),
                        Queue.getInstance().getBuildableItems().stream()
                                .map(Queue.Item::getAssignedLabel)
                                .filter(Objects::nonNull)
                                .map(Label::getName),
                        labelsOfRunningWork(cloud))
                .flatMap(labels -> labels)
                .distinct()
                .filter(labelName -> !tracked.containsKey(labelName))
                .forEach(labelName -> {
                    Label label = Label.get(labelName);
                    HotSpareConfigByLabel governing = label == null ? null : cloud.getHotSpareConfigForLabel(label);
                    if (governing != null) {
                        tracked.put(labelName, governing);
                    }
                });
        return tracked;
    }

    /**
     * @return the labels the builds running on this cloud's agents asked for. Work already running
     *     is part of how busy a label is, so its label keeps a prediction alive even once the queue
     *     has drained.
     */
    private static Stream<String> labelsOfRunningWork(@NonNull EC2Cloud cloud) {
        return agentsOf(cloud)
                .flatMap(computer -> computer.getExecutors().stream())
                .map(MinimumInstanceChecker::assignedLabelOf)
                .filter(Objects::nonNull)
                .map(Label::getName);
    }

    /**
     * @return the label the work on an executor asked for, or {@code null} if the executor is free
     *     or its work carries no label. Work with no label of its own raises no target: it could
     *     have run anywhere, so it says nothing about which hardware to keep warm.
     */
    @CheckForNull
    private static Label assignedLabelOf(@NonNull Executor executor) {
        WorkUnit workUnit = executor.getCurrentWorkUnit();
        return workUnit == null || workUnit.work == null ? null : workUnit.work.getAssignedLabel();
    }

    private static boolean isAssignedTo(@NonNull Executor executor, @NonNull Label label) {
        return isSameLabel(label, assignedLabelOf(executor));
    }

    /**
     * Labels are compared by name because that is what a target is keyed by, so an expression is
     * the same demand however it reached the checker.
     */
    private static boolean isSameLabel(@NonNull Label label, @CheckForNull Label other) {
        return other != null && label.getName().equals(other.getName());
    }

    /**
     * Spreads a shortfall over the templates of a label group, in rotation order so hot spare
     * weights apply.
     *
     * <p>The whole shortfall is handed to the group as one request, so what the leading template
     * cannot supply is asked of the ones behind it before the pass gives up. That matters most for
     * the case the spares exist for: a template is a single spot pool and EC2 fills a request for
     * twenty with however many it has, so a burst that one pool cannot cover is met from the next
     * one in cost order within the same pass instead of a pool's worth per minute. A template out
     * of capacity is also put into the rotation cooldown, the same as for a label request, so the
     * next pass leads with one that can deliver.
     *
     * <p>A template outside the time range it is configured to hold instances in is left out of the
     * request altogether: its schedule is how an admin pays for capacity only when it is wanted.
     */
    private static void provisionAcrossLabelGroup(
            @NonNull EC2Cloud cloud, @NonNull Label label, @NonNull Collection<SlaveTemplate> matching, int toLaunch) {
        List<SlaveTemplate> onDuty = new ArrayList<>();
        for (SlaveTemplate template : cloud.orderTemplatesForLabel(label, matching)) {
            if (keepsWarmInstancesNow(template)) {
                onDuty.add(template);
            } else {
                LOGGER.log(
                        Level.FINE,
                        "{0} is outside the time range it keeps instances in, offering its hot spares to the next template",
                        template);
            }
        }
        if (onDuty.isEmpty()) {
            LOGGER.log(
                    Level.FINE,
                    "No template of label {0} is holding instances at the moment, so its {1} hot spare(s) wait",
                    new Object[] {label.getName(), toLaunch});
            return;
        }

        int launched = cloud.provisionAcrossGroup(onDuty, toLaunch);
        if (launched < toLaunch) {
            LOGGER.log(
                    Level.FINE,
                    "{0} of {1} hot spare(s) for label {2} were not provisioned: every template of the group is at "
                            + "its instance cap, out of capacity, or outside the time range it keeps instances in",
                    new Object[] {toLaunch - launched, toLaunch, label.getName()});
        }
    }

    public static boolean minimumInstancesActive(
            MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig) {
        if (minimumNumberOfInstancesTimeRangeConfig == null) {
            return true;
        }
        LocalTime fromTime = minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeFromAsTime();
        LocalTime toTime = minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeToAsTime();

        LocalDateTime now = LocalDateTime.now(clock);
        LocalTime nowTime = LocalTime.from(now); // No date. Easier for comparison on time only.

        boolean passingMidnight = false;
        if (toTime.isBefore(fromTime)) {
            passingMidnight = true;
        }

        if (passingMidnight) {
            if (nowTime.isAfter(fromTime)) {
                String today = now.getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getDay(today);
            } else if (nowTime.isBefore(toTime)) {
                // We've gone past midnight and want to check yesterday's setting.
                String yesterday = now.minusDays(1).getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getDay(yesterday);
            }
        } else {
            if (nowTime.isAfter(fromTime) && nowTime.isBefore(toTime)) {
                String today = now.getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getDay(today);
            }
        }
        return false;
    }

    @Terminator
    public static void discardIdleInstances() throws Exception {
        LOGGER.fine("Looking for idle instances to discard");
        List<Future<?>> futures = new ArrayList<>();
        Jenkins.get().clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .forEach(cloud -> cloud.getTemplates().stream()
                        .filter(SlaveTemplate::getTerminateIdleDuringShutdown)
                        .forEach(agentTemplate -> idleAgents(agentTemplate).forEach(computer -> {
                            EC2AbstractSlave agent = computer.getNode();
                            if (agent != null) {
                                LOGGER.info(() -> "discarding idle instance " + agent.getInstanceId());
                                futures.add(agent.terminate());
                            }
                        })));
        // Must wait; otherwise task could run too late during shutdown, leading to NoClassDefFoundError.
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
    }
}
