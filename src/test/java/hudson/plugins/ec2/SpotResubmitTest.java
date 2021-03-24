package hudson.plugins.ec2;

import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotInstanceStatus;
import hudson.model.Action;
import hudson.model.Actionable;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import hudson.model.queue.SubTask;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@PowerMockIgnore({"javax.crypto.*", "org.hamcrest.*", "javax.net.ssl.*", "com.sun.org.apache.xerces.*", "javax.xml.*", "org.xml.*"})
@RunWith(PowerMockRunner.class)
@PrepareForTest({Jenkins.class, Queue.class})
public class SpotResubmitTest {

    @Mock
    private SlaveComputer computer;
    @Mock
    private EC2SpotSlave ec2SpotSlave;
    @Mock
    private TaskListener taskListener;
    @Mock
    private Jenkins jenkins;
    @Mock
    private Queue queue;
    @Mock
    private Queue.Task task;
    @Mock
    private Action action;
    @Mock
    private Executor executor;

    /**
     * Setups the queue and task
     */
    @Before
    public void setup() {

        // attaching console handler for debugging
        Logger LOGGER = Logger.getLogger(EC2ComputerLauncher.class.getName());
        LOGGER.addHandler(new ConsoleHandler());

        // Mocking the static classes
        mockStatic(Jenkins.class);
        when(Jenkins.get()).thenReturn(jenkins);
        when(Jenkins.getInstance()).thenReturn(jenkins);
        when(Jenkins.getInstanceOrNull()).thenReturn(jenkins);
        when(Queue.getInstance()).thenReturn(queue);
        // Setting computer offline due to channel termination - disconnection
        when(computer.isOffline()).thenReturn(true);
        when(computer.getOfflineCause()).thenReturn(new OfflineCause.ChannelTermination(null));
        // Mocking executor
        List<Executor> executors = Arrays.asList(executor);
        when(computer.getExecutors()).thenReturn(executors);
        // Mocking executable
        Actionable executable = mock(Actionable.class, withSettings().extraInterfaces(Queue.Executable.class));

        when(executor.getCurrentExecutable()).thenReturn((Queue.Executable) executable);
        // Mocking task list
        SubTask subTask = mock(SubTask.class);
        when(((Queue.Executable) executable).getParent()).thenReturn(subTask);
        when(subTask.getOwnerTask()).thenReturn(task);
        List<Action> actions = Arrays.asList(action);
        when(executable.getActions(Action.class)).thenReturn(actions);
    }

    @Test
    public void testNonSpotInstanceDisconnect() {
        EC2OndemandSlave ec2OndemandSlave = mock(EC2OndemandSlave.class);
        when(computer.getNode()).thenReturn(ec2OndemandSlave);  // set node as ec2ondemandslave
        when(ec2SpotSlave.getRestartSpotInterruption()).thenReturn(true);  // setting restart spot instances to true
        new EC2UnixLauncher().afterDisconnect(computer, taskListener);
        verify(computer).getNode();
        verifyNoMoreInteractions(computer);
    }

    @Test
    public void testSpotInterruptionNoResubmit() {

        EC2SpotSlave ec2SpotSlave = mock(EC2SpotSlave.class);
        when(ec2SpotSlave.getRestartSpotInterruption()).thenReturn(false);  // setting is turned off
        new EC2UnixLauncher().afterDisconnect(computer, taskListener);
        PowerMockito.verifyZeroInteractions(queue);  // verify that the queue has no tasks resubmitted
    }

    @Test
    public void testInterruptExecutors() {

        EC2SpotSlave ec2SpotSlave = mock(EC2SpotSlave.class);
        when(computer.getNode()).thenReturn(ec2SpotSlave);
        when(ec2SpotSlave.getNodeName()).thenReturn("mocked_node");
        when(ec2SpotSlave.getRestartSpotInterruption()).thenReturn(true);
        SpotInstanceRequest spotInstanceRequest = mock(SpotInstanceRequest.class);
        when(ec2SpotSlave.getSpotRequest()).thenReturn(spotInstanceRequest);
        SpotInstanceStatus spotInstanceStatus = mock(SpotInstanceStatus.class);
        when(spotInstanceRequest.getStatus()).thenReturn(spotInstanceStatus);
        when(spotInstanceStatus.getCode()).thenReturn("instance-terminated-by-price");
        new EC2UnixLauncher().afterDisconnect(computer, taskListener);
        verify(executor).interrupt(Result.ABORTED, new EC2ComputerLauncher.EC2SpotInterruptedCause(ec2SpotSlave.getNodeName()));
    }

    @Test
    public void testSpotInterruptionResubmitQueue() throws IOException, Descriptor.FormException {

        when(computer.getNode()).thenReturn(ec2SpotSlave);
        when(ec2SpotSlave.getNodeName()).thenReturn("mocked_node");
        // Mocking the spot interruption settings and events
        when(ec2SpotSlave.getRestartSpotInterruption()).thenReturn(true);
        SpotInstanceRequest spotInstanceRequest = mock(SpotInstanceRequest.class);
        when(ec2SpotSlave.getSpotRequest()).thenReturn(spotInstanceRequest);
        SpotInstanceStatus spotInstanceStatus = mock(SpotInstanceStatus.class);
        when(spotInstanceRequest.getStatus()).thenReturn(spotInstanceStatus);
        when(spotInstanceStatus.getCode()).thenReturn("instance-terminated-by-price");

        // Verifying that the queue task was rescheduled
        new EC2UnixLauncher().afterDisconnect(computer, taskListener);
        verify(queue).schedule2(eq(task), anyInt(), eq(Arrays.asList(action)));
    }
}
