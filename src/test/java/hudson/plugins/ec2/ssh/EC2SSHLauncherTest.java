package hudson.plugins.ec2.ssh;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import hudson.model.TaskListener;
import hudson.plugins.ec2.HostKeyVerificationStrategyEnum;
import hudson.plugins.ec2.MockEC2Computer;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Level;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class EC2SSHLauncherTest {

    private JenkinsRule r;

    private final LogRecorder loggerRule = new LogRecorder();

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void testServerKeyVerifier() throws Exception {
        for (String publicKeyFile : List.of(
                "ssh_host_dss_1024.pub",
                "ssh_host_rsa_1024.pub",
                "ssh_host_rsa_2048.pub",
                "ssh_host_rsa_3072.pub",
                "ssh_host_rsa_4096.pub",
                // Special case of Open SSH Certificate
                // ssh-keygen -t rsa -b 2048 -f ./ssh_host_rsa -N ""
                // ssh-keygen -s ./ssh_host_rsa -I ec2SshLauncherTest -h -n localhost -V +0s:+999999999s
                // ./ssh_host_rsa.pub
                "ssh_host_rsa-cert.pub")) {

            String sshHostKeyPath = Files.readString(Path.of(getClass()
                            .getClassLoader()
                            .getResource("hudson/plugins/ec2/ssh/" + publicKeyFile)
                            .getPath()))
                    .trim();

            MockEC2Computer computer = MockEC2Computer.createComputer(publicKeyFile);
            r.jenkins.addNode(computer.getNode());

            computer.getSlaveTemplate().setHostKeyVerificationStrategy(HostKeyVerificationStrategyEnum.OFF);
            assertTrue(new EC2SSHLauncher.ServerKeyVerifierImpl(computer, TaskListener.NULL)
                    .verifyServerKey(
                            null,
                            null,
                            PublicKeyEntry.parsePublicKeyEntry(sshHostKeyPath).resolvePublicKey(null, null, null)));
            computer.getSlaveTemplate().setHostKeyVerificationStrategy(HostKeyVerificationStrategyEnum.ACCEPT_NEW);
            assertTrue(new EC2SSHLauncher.ServerKeyVerifierImpl(computer, TaskListener.NULL)
                    .verifyServerKey(
                            null,
                            null,
                            PublicKeyEntry.parsePublicKeyEntry(sshHostKeyPath).resolvePublicKey(null, null, null)));

            r.jenkins.removeNode(computer.getNode());
        }
    }

    @Test
    void testExecuteRemoteWithLogCapture() throws Exception {
        // Create a mock computer and template
        MockEC2Computer computer = MockEC2Computer.createComputer("test-computer");
        r.jenkins.addNode(computer.getNode());

        // Set up the computer to enable init script log collection
        computer.getSlaveTemplate().setCollectInitScriptLogs(true);

        // Create a test launcher
        EC2UnixLauncher launcher = new EC2UnixLauncher();

        // Create mock objects for SSH session and streams
        ClientSession mockSession = mock(ClientSession.class);
        ChannelExec mockChannel = mock(ChannelExec.class);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        // Mock the channel creation and execution
        when(mockSession.createExecChannel(anyString())).thenReturn(mockChannel);
        when(mockChannel.open()).thenReturn(mock(OpenFuture.class));
        when(mockChannel.waitFor(any(), anyLong())).thenReturn(EnumSet.of(ClientChannelEvent.CLOSED));
        when(mockChannel.getExitStatus()).thenReturn(0);

        // Set up channel to capture output
        doAnswer(invocation -> {
                    OutputStream out = invocation.getArgument(0);
                    out.write("Test init script output\n".getBytes());
                    out.flush();
                    return null;
                })
                .when(mockChannel)
                .setOut(any(OutputStream.class));

        doAnswer(invocation -> {
                    OutputStream err = invocation.getArgument(0);
                    err.write("Test error output\n".getBytes());
                    err.flush();
                    return null;
                })
                .when(mockChannel)
                .setErr(any(OutputStream.class));

        // Create a TaskListener to capture the output
        ByteArrayOutputStream logOutput = new ByteArrayOutputStream();
        TaskListener listener = new StreamTaskListener(logOutput);

        try {
            // Test the executeRemote method with log collection enabled
            loggerRule.capture(3).record("hudson.plugins.ec2.ssh.EC2SSHLauncher", Level.ALL);
            boolean result = launcher.executeRemote(
                    mockSession,
                    "echo 'Hello from init script'",
                    System.out,
                    true, // collectOutput = true
                    listener);

            // Verify the execution was successful
            assertTrue(result);

            // Verify that output was captured and logged
            assertTrue(
                    loggerRule.getMessages().stream().anyMatch(message -> message.contains("Hello from init script")));

            // Verify that the channel was properly configured for output capture
            verify(mockChannel).setOut(any(OutputStream.class));
            verify(mockChannel).setErr(any(OutputStream.class));

        } finally {
            r.jenkins.removeNode(computer.getNode());
        }
    }

    @Test
    void testExecuteRemoteWithoutLogCapture() throws Exception {
        // Create a mock computer and template
        MockEC2Computer computer = MockEC2Computer.createComputer("test-computer-no-logs");
        r.jenkins.addNode(computer.getNode());

        // Set up the computer to disable init script log collection
        computer.getSlaveTemplate().setCollectInitScriptLogs(false);

        // Create a test launcher
        EC2UnixLauncher launcher = new EC2UnixLauncher();

        // Create mock objects for SSH session
        ClientSession mockSession = mock(ClientSession.class);
        ChannelExec mockChannel = mock(ChannelExec.class);

        // Mock the channel creation and execution
        when(mockSession.createExecChannel(anyString())).thenReturn(mockChannel);
        when(mockChannel.open()).thenReturn(mock(OpenFuture.class));
        when(mockChannel.waitFor(any(), anyLong())).thenReturn(EnumSet.of(ClientChannelEvent.CLOSED));
        when(mockChannel.getExitStatus()).thenReturn(0);

        // Create a TaskListener
        ByteArrayOutputStream logOutput = new ByteArrayOutputStream();
        TaskListener listener = new StreamTaskListener(logOutput);

        try {
            // Test the executeRemote method with log collection disabled
            loggerRule.capture(3).record("hudson.plugins.ec2.ssh.EC2SSHLauncher", Level.ALL);
            boolean result = launcher.executeRemote(
                    mockSession,
                    "echo 'Hello from init script'",
                    System.out,
                    false, // collectOutput = false
                    listener);

            // Verify the execution was successful
            assertTrue(result);

            assertFalse(
                    loggerRule.getMessages().stream().anyMatch(message -> message.contains("Hello from init script")));

            // Verify that output streams were not set up for capture
            verify(mockChannel, never()).setOut(any(OutputStream.class));
            verify(mockChannel, never()).setErr(any(OutputStream.class));

        } finally {
            r.jenkins.removeNode(computer.getNode());
        }
    }

    @Test
    void testExecuteRemoteBackwardCompatibility() throws Exception {
        // Test the original 3-parameter executeRemote method still works
        MockEC2Computer computer = MockEC2Computer.createComputer("test-backward-compat");
        r.jenkins.addNode(computer.getNode());

        EC2UnixLauncher launcher = new EC2UnixLauncher();

        // Create mock objects
        ClientSession mockSession = mock(ClientSession.class);
        ChannelExec mockChannel = mock(ChannelExec.class);

        when(mockSession.createExecChannel(anyString())).thenReturn(mockChannel);
        when(mockChannel.open()).thenReturn(mock(OpenFuture.class));
        when(mockChannel.waitFor(any(), anyLong())).thenReturn(EnumSet.of(ClientChannelEvent.CLOSED));
        when(mockChannel.getExitStatus()).thenReturn(0);

        try {
            // Test the original executeRemote method (should default to no log capture)
            boolean result = launcher.executeRemote(mockSession, "echo 'Hello'", System.out);

            // Verify the execution was successful
            assertTrue(result);

            // Verify that this calls the new method with collectOutput=false
            verify(mockChannel, never()).setOut(any(OutputStream.class));

        } finally {
            r.jenkins.removeNode(computer.getNode());
        }
    }
}
