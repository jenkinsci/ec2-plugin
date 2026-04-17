package hudson.plugins.ec2.ssh;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.util.SSHClientHelper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedStatic;
import software.amazon.awssdk.core.exception.SdkException;

@WithJenkins
class LaunchRemotingAgentThreadLeakTest {

    private EC2Computer mockEC2Computer;
    private TaskListener mockListener;
    private PrintStream mockPS;
    private EC2AbstractSlave mockNode;
    private SlaveTemplate mockTemplate;
    private EC2Cloud mockCloud;
    private MockedStatic<SSHClientHelper> mockStaticSSHClientHelper;
    private SSHClientHelper mockSSHClientHelper;
    private SshClient mockSshClient;
    private ClientSession mockClientSession;
    private AuthFuture mockAuthFuture;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        mockEC2Computer = mock(EC2Computer.class);
        mockListener = mock(TaskListener.class);
        mockPS = mock(PrintStream.class);
        mockNode = mock(EC2AbstractSlave.class);
        mockTemplate = mock(SlaveTemplate.class);
        mockCloud = mock(EC2Cloud.class);
        mockStaticSSHClientHelper = mockStatic(SSHClientHelper.class);
        mockSSHClientHelper = mock(SSHClientHelper.class);
        mockSshClient = mock(SshClient.class);
        mockClientSession = mock(ClientSession.class);
        mockAuthFuture = mock(AuthFuture.class);

        when(mockEC2Computer.getNode()).thenReturn(mockNode);
        when(mockEC2Computer.getCloud()).thenReturn(mockCloud);
        when(mockListener.getLogger()).thenReturn(mockPS);

        mockStaticSSHClientHelper.when(SSHClientHelper::getInstance).thenReturn(mockSSHClientHelper);
        when(mockSSHClientHelper.setupSshClient(any())).thenReturn(mockSshClient);
    }

    @AfterEach
    void tearDown() {
        mockStaticSSHClientHelper.close();
    }

    @Test
    void sshClientIsCleanedUp_whenConnectToSshThrows() throws Exception {
        EC2WindowsSSHLauncher launcher = spy(new EC2WindowsSSHLauncher());
        doThrow(SdkException.builder().message("simulated connect failure").build())
                .when(launcher)
                .connectToSsh(eq(mockSshClient), any(), any(), any());

        assertThrows(
                SdkException.class,
                () -> launcher.launchRemotingAgent(
                        mockEC2Computer, mockListener, "java -jar remoting.jar", mockTemplate, 10_000L, mockPS));

        verify(mockSshClient).stop();
        verify(mockSshClient).close();
    }

    @Test
    void sshClientIsCleanedUp_whenCreateExecChannelThrows() throws Exception {
        EC2WindowsSSHLauncher launcher = spy(new EC2WindowsSSHLauncher());
        doReturn(mockClientSession).when(launcher).connectToSsh(eq(mockSshClient), any(), any(), any());
        when(mockClientSession.auth()).thenReturn(mockAuthFuture);
        when(mockClientSession.createExecChannel(any(), any(), any(), any()))
                .thenThrow(new IOException("simulated channel failure"));

        assertThrows(
                IOException.class,
                () -> launcher.launchRemotingAgent(
                        mockEC2Computer, mockListener, "java -jar remoting.jar", mockTemplate, 10_000L, mockPS));

        verify(mockSshClient).stop();
        verify(mockSshClient).close();
    }

    @Test
    void sshClientIsNotClosedEarly_whenLaunchSucceeds() throws Exception {
        EC2WindowsSSHLauncher launcher = spy(new EC2WindowsSSHLauncher());
        ChannelExec mockChannelExec = mock(ChannelExec.class);
        OpenFuture mockOpenFuture = mock(OpenFuture.class);

        doReturn(mockClientSession).when(launcher).connectToSsh(eq(mockSshClient), any(), any(), any());
        when(mockClientSession.auth()).thenReturn(mockAuthFuture);
        when(mockClientSession.createExecChannel(any(), any(), any(), any())).thenReturn(mockChannelExec);
        when(mockChannelExec.open()).thenReturn(mockOpenFuture);
        when(mockChannelExec.getInvertedOut()).thenReturn(mock(InputStream.class));
        when(mockChannelExec.getInvertedIn()).thenReturn(mock(OutputStream.class));

        launcher.launchRemotingAgent(
                mockEC2Computer, mockListener, "java -jar remoting.jar", mockTemplate, 10_000L, mockPS);

        verify(mockSshClient, never()).stop();
        verify(mockSshClient, never()).close();
    }
}
