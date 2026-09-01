package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import hudson.model.PeriodicWork;
import hudson.model.Result;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecution;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mock;
import org.mockito.internal.stubbing.answers.ThrowsException;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

/**
 * @author Alicia Doblas
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@WithJenkins
class EC2StepTest {
    @Mock
    private EC2Cloud cl;

    @Mock
    private SlaveTemplate st;

    @Mock
    private EC2AbstractSlave instance;

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        r = rule;
        r.jenkins.clouds.clear();
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(st);

        when(cl.getCloudName()).thenReturn("myCloud");
        when(cl.getDisplayName()).thenReturn("myCloud");
        when(cl.getTemplates()).thenReturn(templates);
        when(cl.getTemplate(anyString())).thenReturn(st);
        r.jenkins.clouds.add(cl);

        when(instance.getNodeName()).thenReturn("nodeName");
        List<EC2AbstractSlave> slaves = Collections.singletonList(instance);
        when(st.provision(anyInt(), any())).thenReturn(slaves);
    }

    @Test
    void testExpiredConnection() {
        when(cl.connect()).thenCallRealMethod();
        when(cl.createCredentialsProvider()).thenCallRealMethod();

        // not expired ec2 client
        Ec2Client notExpiredClient = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
        AmazonEC2FactoryMockImpl.mock = notExpiredClient;
        assertSame(notExpiredClient, cl.connect(), "EC2 client not expired should be reused");

        // expired ec2 client
        //  based on a real exception
        //  > Request has expired. (Service: AmazonEC2; Status Code: 400; Error Code: RequestExpired; Request ID:
        // 00000000-0000-0000-0000-000000000000)
        Ec2Exception.Builder expiredExceptionBuilder = Ec2Exception.builder();
        expiredExceptionBuilder.message("Request has expired");
        expiredExceptionBuilder.statusCode(400);
        expiredExceptionBuilder.awsErrorDetails(AwsErrorDetails.builder()
                .serviceName("AmazonEC2")
                .errorCode("RequestExpired")
                .build());
        expiredExceptionBuilder.requestId("00000000-0000-0000-0000-000000000000");

        Ec2Client expiredClient =
                AmazonEC2FactoryMockImpl.createAmazonEC2Mock(new ThrowsException(expiredExceptionBuilder.build()));
        AmazonEC2FactoryMockImpl.mock = expiredClient;
        PeriodicWork work = PeriodicWork.all().get(EC2Cloud.EC2ConnectionUpdater.class);
        assertNotNull(work);
        work.run();
        assertNotSame(expiredClient, cl.connect(), "EC2 client should be re-created when it is expired");
    }

    @Test
    void bootInstance() throws Exception {
        WorkflowJob boot = r.createProject(WorkflowJob.class);
        String builtInNodeLabel = r.jenkins.getSelfLabel().getName(); // compatibility with 2.307+
        boot.setDefinition(new CpsFlowDefinition(
                " node('" + builtInNodeLabel + "') {\n" + "    def X = ec2 cloud: 'myCloud', template: 'aws-CentOS-7'\n"
                        + "}",
                true));
        WorkflowRun b = r.buildAndAssertSuccess(boot);
        r.assertLogContains("SUCCESS", b);
    }

    /**
     * The {@code ec2} step provisions an instance without ever registering a Jenkins node, so no
     * {@code NodeProvisioner}/{@code ComputerListener} machinery advances or completes its cloud-stats activity -- the
     * step owns the whole lifecycle. It must therefore record exactly one activity and drive it to COMPLETED itself,
     * advancing through OPERATING first so the clean completion carries no attachment: cloud-stats attaches a
     * premature-completion WARN to a PROVISIONING -&gt; COMPLETED jump, and this step deliberately avoids that.
     */
    @Test
    void bootInstanceRecordsCompletedCloudStatsActivity() throws Exception {
        WorkflowJob boot = r.createProject(WorkflowJob.class);
        String builtInNodeLabel = r.jenkins.getSelfLabel().getName(); // compatibility with 2.307+
        boot.setDefinition(new CpsFlowDefinition(
                " node('" + builtInNodeLabel + "') {\n" + "    def X = ec2 cloud: 'myCloud', template: 'aws-CentOS-7'\n"
                        + "}",
                true));
        r.buildAndAssertSuccess(boot);

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertEquals(1, activities.size(), "the ec2 step must record exactly one cloud-stats activity");
        ProvisioningActivity activity = activities.get(0);
        assertEquals(
                ProvisioningActivity.Phase.COMPLETED,
                activity.getCurrentPhase(),
                "the step owns the whole lifecycle and must complete its own activity");
        assertNotNull(
                activity.getPhaseExecution(ProvisioningActivity.Phase.OPERATING),
                "the step must advance through OPERATING, not jump straight from PROVISIONING to COMPLETED");
        assertEquals(
                ProvisioningActivity.Status.OK, activity.getStatus(), "a successful boot must not be marked as failed");
        // Advancing through OPERATING is what keeps the completion clean: no premature-completion WARN, no FAIL.
        for (PhaseExecution execution : activity.getPhaseExecutions().values()) {
            if (execution != null) {
                assertTrue(
                        execution.getAttachments().isEmpty(),
                        "a clean step completion must record no attachments, but " + execution.getPhase() + " had "
                                + execution.getAttachments());
            }
        }
    }

    /**
     * The FAIL branch of the step's self-owned lifecycle: when provisioning yields no instance (empty list from the
     * template -- capacity exhausted, cap reached, or a swallowed API error), the step must not leave its activity
     * dangling in PROVISIONING. It completes the activity as FAIL with the reason attached, and rethrows so the build
     * still fails.
     */
    @Test
    void bootInstanceRecordsFailedCloudStatsActivityWhenNoInstanceProvisioned() throws Exception {
        // The template accepts the request but yields no instance; the step turns that into an actionable failure.
        when(st.provision(anyInt(), any())).thenReturn(Collections.emptyList());

        WorkflowJob boot = r.createProject(WorkflowJob.class);
        String builtInNodeLabel = r.jenkins.getSelfLabel().getName(); // compatibility with 2.307+
        boot.setDefinition(new CpsFlowDefinition(
                " node('" + builtInNodeLabel + "') {\n" + "    def X = ec2 cloud: 'myCloud', template: 'aws-CentOS-7'\n"
                        + "}",
                true));
        r.buildAndAssertStatus(Result.FAILURE, boot);

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertEquals(1, activities.size(), "a failed provision must still record exactly one cloud-stats activity");
        ProvisioningActivity activity = activities.get(0);
        assertEquals(
                ProvisioningActivity.Phase.COMPLETED,
                activity.getCurrentPhase(),
                "a failed provision must complete its activity, not leave it dangling in PROVISIONING");
        assertEquals(
                ProvisioningActivity.Status.FAIL,
                activity.getStatus(),
                "a provision that yielded no instance must be marked FAIL");
        assertNotNull(
                CloudStatsTestSupport.failAttachment(activity),
                "the failure must carry a FAIL attachment so the reason is visible in the stats");
    }

    @Test
    void boot_noCloud() throws Exception {
        WorkflowJob boot = r.createProject(WorkflowJob.class);
        String builtInNodeLabel = r.jenkins.getSelfLabel().getName(); // compatibility with 2.307+
        boot.setDefinition(new CpsFlowDefinition(
                " node('" + builtInNodeLabel + "') {\n"
                        + "    def X = ec2 cloud: 'dummyCloud', template: 'aws-CentOS-7'\n"
                        + "    X.boot()\n"
                        + "}",
                true));
        WorkflowRun b = r.buildAndAssertStatus(Result.FAILURE, boot);
        r.assertLogContains("Error in AWS Cloud. Please review EC2 settings in Jenkins configuration.", b);
        r.assertLogContains("FAILURE", b);
    }

    @Test
    void boot_noTemplate() throws Exception {
        when(cl.getTemplate(anyString())).thenReturn(null);

        WorkflowJob boot = r.createProject(WorkflowJob.class);
        String builtInNodeLabel = r.jenkins.getSelfLabel().getName(); // compatibility with 2.307+
        boot.setDefinition(new CpsFlowDefinition(
                " node('" + builtInNodeLabel + "') {\n"
                        + "    def X = ec2 cloud: 'myCloud', template: 'dummyTemplate'\n"
                        + "    X.boot()\n"
                        + "}",
                true));
        WorkflowRun b = r.buildAndAssertStatus(Result.FAILURE, boot);
        r.assertLogContains("Error in AWS Cloud. Please review AWS template defined in Jenkins configuration.", b);
        r.assertLogContains("FAILURE", b);
    }
}
