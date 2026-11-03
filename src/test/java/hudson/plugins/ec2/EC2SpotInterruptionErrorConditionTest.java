package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class EC2SpotInterruptionErrorConditionTest {

    private static final String LABEL = "reclaimable";

    @AfterEach
    void tearDown() {
        EC2SpotInterruptions.reset();
    }

    /**
     * The condition is only worth anything if a pipeline can name it, which takes the symbol, the
     * descriptor and the optional Pipeline dependencies all being right. A build that fails on its
     * own account is the case that must not be retried, so it doubles as the check that the
     * condition is actually consulted rather than ignored.
     */
    @Test
    void testAFailureOfTheBuildItselfIsNotRetried(JenkinsRule r) throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, "no-retry");
        job.setDefinition(new CpsFlowDefinition("""
                retry(count: 2, conditions: [ec2SpotInterruption()]) {
                    node {
                        echo 'ATTEMPTED'
                        error 'the build itself failed'
                    }
                }
                """, true));

        WorkflowRun run = r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        String log = r.getLog(run);
        assertEquals(
                1,
                attempts(log),
                "a build that failed on its own account must not be retried as a lost agent, but the build log was:\n"
                        + log);
    }

    /**
     * The case the condition exists for: the agent goes away mid-build because EC2 took the
     * instance back, and the build is given another one rather than failing for something it did
     * not do.
     *
     * <p>Only the agent that is about to be taken away waits around, so the attempt that follows it
     * finishes as soon as it has somewhere to run.
     */
    @Test
    void testAnAgentLostToAnInterruptionIsRetriedOnAnother(JenkinsRule r) throws Exception {
        Node doomed = r.createOnlineSlave(Label.get(LABEL));
        WorkflowJob job = r.createProject(WorkflowJob.class, "retry");
        job.setDefinition(new CpsFlowDefinition("""
                retry(count: 2, conditions: [ec2SpotInterruption()]) {
                    node('%s') {
                        echo 'ATTEMPTED'
                        if (env.NODE_NAME == '%s') {
                            sh 'sleep 120'
                        }
                    }
                }
                """.formatted(LABEL, doomed.getNodeName()), true));

        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        r.waitForMessage("ATTEMPTED", run);

        // Somewhere for the second attempt to go, since the agent it is on is about to disappear.
        r.createOnlineSlave(Label.get(LABEL));
        EC2SpotInterruptions.note(doomed.getNodeName());
        r.jenkins.removeNode(doomed);

        r.waitForCompletion(run);
        String log = r.getLog(run);
        r.assertBuildStatus(Result.SUCCESS, run);
        assertEquals(2, attempts(log), "the build should have been tried again on another agent, log was:\n" + log);
    }

    /**
     * The same thing written the way most people write pipelines. Declarative parses its options
     * separately from the scripted step, so accepting the condition there is its own question, and
     * the answer has to include the retried stage being given a fresh agent.
     */
    @Test
    void testADeclarativeStageOptionRetriesOnAnother(JenkinsRule r) throws Exception {
        Node doomed = r.createOnlineSlave(Label.get(LABEL));
        WorkflowJob job = r.createProject(WorkflowJob.class, "declarative");
        job.setDefinition(new CpsFlowDefinition("""
                pipeline {
                    agent none
                    stages {
                        stage('Build') {
                            agent { label '%s' }
                            options {
                                retry(count: 2, conditions: [ec2SpotInterruption()])
                            }
                            steps {
                                echo 'ATTEMPTED'
                                script {
                                    if (env.NODE_NAME == '%s') {
                                        sh 'sleep 120'
                                    }
                                }
                            }
                        }
                    }
                }
                """.formatted(LABEL, doomed.getNodeName()), true));

        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        r.waitForMessage("ATTEMPTED", run);

        r.createOnlineSlave(Label.get(LABEL));
        EC2SpotInterruptions.note(doomed.getNodeName());
        r.jenkins.removeNode(doomed);

        r.waitForCompletion(run);
        String log = r.getLog(run);
        r.assertBuildStatus(Result.SUCCESS, run);
        assertEquals(2, attempts(log), "the stage should have been tried again on another agent, log was:\n" + log);
    }

    private static int attempts(String log) {
        return log.split("ATTEMPTED", -1).length - 1;
    }
}
