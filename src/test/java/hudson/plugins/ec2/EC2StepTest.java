package hudson.plugins.ec2;


import hudson.model.Label;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.plugins.ec2.util.PluginTestRule;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

/**
 * @author Alicia Doblas
 */
@PowerMockIgnore({"javax.crypto.*", "org.hamcrest.*", "javax.net.ssl.*"})
@RunWith(PowerMockRunner.class)
@PrepareForTest({EC2AbstractSlave.class, SlaveTemplate.class})
public class EC2StepTest {
    @Rule
    public PluginTestRule r = new PluginTestRule();

    @Mock
    private AmazonEC2Cloud cl;

    @Mock
    private SlaveTemplate st;

    @Mock
    private EC2AbstractSlave instance;

    @Before
    public void setup () throws Exception {
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(st);

        when(cl.getCloudName()).thenReturn("myCloud");
        when(cl.getDisplayName()).thenReturn("myCloud");
        when(cl.getTemplates()).thenReturn(templates);
        when(cl.getTemplate(anyString())).thenReturn(st);
        r.addCloud(cl);

        when(instance.getNodeName()).thenReturn("nodeName");
    }


    @Test
    public void bootInstance() throws Exception {

        when(st.provision(any(TaskListener.class),any(Label.class),any(EnumSet.class))).thenReturn(instance);

        WorkflowJob boot = r.jenkins.createProject(WorkflowJob.class, "EC2Test");
        boot.setDefinition(new CpsFlowDefinition(
                " node('master') {\n" +
                        "    def X = ec2 cloud: 'myCloud', template: 'aws-CentOS-7'\n" +
                        "}" , true));
        WorkflowRun b = r.assertBuildStatusSuccess(boot.scheduleBuild2(0));
        r.assertLogContains("SUCCESS", b);
    }

    @Test
    public void boot_noCloud() throws Exception {

        when(st.provision(any(TaskListener.class),any(Label.class),any(EnumSet.class))).thenReturn(instance);

        WorkflowJob boot = r.jenkins.createProject(WorkflowJob.class, "EC2Test");
        boot.setDefinition(new CpsFlowDefinition(
                " node('master') {\n" +
                        "    def X = ec2 cloud: 'dummyCloud', template: 'aws-CentOS-7'\n" +
                        "    X.boot()\n" +
                        "}" , true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, boot.scheduleBuild2(0).get());
        r.assertLogContains("Error in AWS Cloud. Please review EC2 settings in Jenkins configuration.", b);
        r.assertLogContains("FAILURE", b);
    }


    @Test
    public void boot_noTemplate() throws Exception {

        when(cl.getTemplate(anyString())).thenReturn(null);
        when(st.provision(any(TaskListener.class),any(Label.class),any(EnumSet.class))).thenReturn(instance);

        WorkflowJob boot = r.jenkins.createProject(WorkflowJob.class, "EC2Test");
        boot.setDefinition(new CpsFlowDefinition(
                " node('master') {\n" +
                        "    def X = ec2 cloud: 'myCloud', template: 'dummyTemplate'\n" +
                        "    X.boot()\n" +
                        "}" , true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, boot.scheduleBuild2(0).get());
        r.assertLogContains("Error in AWS Cloud. Please review AWS template defined in Jenkins configuration.", b);
        r.assertLogContains("FAILURE", b);
    }

    @After
    public void teardown () {
        r.jenkins.clouds.clear();
    }


}
