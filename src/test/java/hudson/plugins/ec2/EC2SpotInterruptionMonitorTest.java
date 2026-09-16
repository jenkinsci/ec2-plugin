package hudson.plugins.ec2;

import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class EC2SpotInterruptionMonitorTest {

    private static final String LABEL = "reclaimable";

    /**
     * Whoever has to account for the failure usually has the build log and nothing else, so the
     * reason has to arrive there and not only in the agent's own log. Reaching the log of a build
     * that is still running means going from the agent's executor to the build behind it, which for
     * Pipeline is not the build itself but the placeholder standing in for it.
     */
    @Test
    void testABuildIsToldWhyItsAgentIsGoingAway(JenkinsRule r) throws Exception {
        Node agent = r.createOnlineSlave(Label.get(LABEL));
        WorkflowJob job = r.createProject(WorkflowJob.class, "told");
        job.setDefinition(new CpsFlowDefinition("""
                node('%s') {
                    echo 'RUNNING'
                    sleep 120
                }
                """.formatted(LABEL), true));

        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        r.waitForMessage("RUNNING", run);

        Computer computer = agent.toComputer();
        EC2SpotInterruptionMonitor.tellBuildsUnderWay(computer, "EC2 is reclaiming spot instance i-0123456789abcdef0");

        r.waitForMessage("EC2 is reclaiming spot instance i-0123456789abcdef0", run);

        run.doStop();
        r.waitForCompletion(run);
    }
}
