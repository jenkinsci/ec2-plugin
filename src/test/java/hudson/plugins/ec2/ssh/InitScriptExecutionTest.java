package hudson.plugins.ec2.ssh;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import hudson.model.TaskListener;
import hudson.plugins.ec2.ConnectionStrategy;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.util.KeyHelper;
import hudson.plugins.ec2.util.KeyPair;
import hudson.plugins.ec2.util.SSHClientHelper;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.CloseableScpClient;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedStatic;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;

@WithJenkins
class InitScriptExecutionTest {

    private EC2Computer mockEC2Computer;
    private TaskListener mockListener;
    private PrintStream mockPS;
    private PrintWriter mockPW;
    private EC2AbstractSlave mockNode;
    private SlaveTemplate mockTemplate;
    private EC2Cloud mockCloud;
    private KeyPair mockKp;
    private KeyPairInfo mockKPInfo;
    private Instance mockInstance;
    private MockedStatic<SSHClientHelper> mockStaticSSHClientHelper;
    private MockedStatic<KeyHelper> mockStaticKeyHelper;
    private SSHClientHelper mockSSHClientHelper;
    private ClientSession mockClientSession;
    private SshClient mockSshClient;
    private ScpClient mockScpClient;
    private MockedStatic<CloseableScpClient> mockStaticClosableScpClient;
    private CloseableScpClient mockClosableScpClient;
    private MockedStatic<ScpClientCreator> mockStaticScpClientCreator;
    private ScpClientCreator mockScpClientCreator;
    private ConnectFuture mockConnectFuture;
    private AuthFuture mockAuthFuture;
    private java.security.KeyPair mockKeyPair;
    private ChannelExec mockAgentChannelExec;
    private OpenFuture mockOpenFuture;
    private final String mockAdmin = "jenkinsAdmin";
    private final String mockHost = "example.com";
    private EC2UnixLauncher launcher;

    private final LogRecorder loggerRule = new LogRecorder();

    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        jenkins = rule;
        // Set up the test environment
        mockEC2Computer = mock(EC2Computer.class);
        mockListener = mock(TaskListener.class);
        mockPS = mock(PrintStream.class);
        mockPW = new PrintWriter(System.out);
        mockTemplate = mock(SlaveTemplate.class);
        mockCloud = mock(EC2Cloud.class);
        mockKp = mock(KeyPair.class);
        mockKPInfo = mock(KeyPairInfo.class);
        mockInstance = mock(Instance.class);
        mockStaticSSHClientHelper = mockStatic(SSHClientHelper.class);
        mockStaticKeyHelper = mockStatic(KeyHelper.class);
        mockSSHClientHelper = mock(SSHClientHelper.class);
        mockClientSession = mock(ClientSession.class);
        mockSshClient = mock(SshClient.class);
        mockScpClient = mock(ScpClient.class);
        mockStaticClosableScpClient = mockStatic(CloseableScpClient.class);
        mockClosableScpClient = mock(CloseableScpClient.class);
        mockStaticScpClientCreator = mockStatic(ScpClientCreator.class);
        mockScpClientCreator = mock(ScpClientCreator.class);
        mockConnectFuture = mock(ConnectFuture.class);
        mockAuthFuture = mock(AuthFuture.class);
        mockKeyPair = mock(java.security.KeyPair.class);
        mockAgentChannelExec = mock(ChannelExec.class);
        mockOpenFuture = mock(OpenFuture.class);
    }

    @AfterEach
    void tearDown() {
        mockStaticSSHClientHelper.close();
        mockStaticKeyHelper.close();
        mockStaticClosableScpClient.close();
        mockStaticScpClientCreator.close();
    }

    @Test
    void testInitScriptExecutionSuccessful() throws Exception {
        String initScript = "echo 'Hello World'";
        mockNode = getMockNodeTemplate(initScript);
        launcher = spy(new EC2UnixLauncher());

        // Define behaviour.
        defineBehaviourCommon();
        doReturn(initScript).when(launcher).buildUpCommand(mockEC2Computer, "/tmp/init.sh");
        doNothing().when(mockClientSession).executeRemoteCommand(initScript, mockPS, mockPS, null);
        doReturn("touch ~/.hudson-run-init").when(launcher).buildUpCommand(mockEC2Computer, "touch ~/.hudson-run-init");
        doNothing().when(mockClientSession).executeRemoteCommand("touch ~/.hudson-run-init", mockPS, mockPS, null);

        // Execute test.
        loggerRule.capture(3).record("hudson.plugins.ec2.ssh.EC2SSHLauncher", Level.ALL);
        launcher.launch(mockEC2Computer, mockListener);
        // Test for marker doesn't exists.
        assertTrue(loggerRule.getMessages().stream()
                .anyMatch(message -> message.contains("Failed to execute remote command: test -e ~/.hudson-run-init")));
        // Test for successful init script execution.
        assertTrue(loggerRule.getMessages().stream()
                .anyMatch(message ->
                        message.contains("Init script executed successfully and creating ~/.hudson-run-init")));
    }

    @Test
    void testInitScriptExecutionFailure() throws Exception {
        String initScript = "exit 1";
        mockNode = getMockNodeTemplate(initScript);
        launcher = spy(new EC2UnixLauncher());

        // Define behaviour.
        defineBehaviourCommon();
        doReturn(initScript).when(launcher).buildUpCommand(mockEC2Computer, "/tmp/init.sh");
        doThrow(new IOException("Command failed"))
                .when(mockClientSession)
                .executeRemoteCommand(initScript, mockPS, mockPS, null);

        // Execute test.
        loggerRule.capture(5).record("hudson.plugins.ec2.ssh.EC2SSHLauncher", Level.ALL);
        launcher.launch(mockEC2Computer, mockListener);
        // Test for marker doesn't exists.
        assertTrue(loggerRule.getMessages().stream()
                .anyMatch(message -> message.contains("Failed to execute remote command: test -e ~/.hudson-run-init")));
        // Test for failed init script execution.
        assertTrue(loggerRule.getMessages().stream()
                .anyMatch(message -> message.contains("Failed to execute remote command: exit 1")));
        assertTrue(loggerRule.getMessages().stream()
                .anyMatch(message -> message.contains("Failed to execute init script on i-initscripttest")));
    }

    @Test
    void testInitScriptExecutionSuccessfulButMarkerCreationFailure() throws Exception {
        String initScript = "echo 'Hello World'";
        mockNode = getMockNodeTemplate(initScript);
        launcher = spy(new EC2UnixLauncher());

        // Define behaviour.
        defineBehaviourCommon();
        doReturn(initScript).when(launcher).buildUpCommand(mockEC2Computer, "/tmp/init.sh");
        doNothing().when(mockClientSession).executeRemoteCommand(initScript, mockPS, mockPS, null);
        doReturn("touch ~/.hudson-run-init").when(launcher).buildUpCommand(mockEC2Computer, "touch ~/.hudson-run-init");
        doThrow(new IOException("Command failed"))
                .when(mockClientSession)
                .executeRemoteCommand("touch ~/.hudson-run-init", mockPS, mockPS, null);

        // Execute test.
        loggerRule.capture(5).record("hudson.plugins.ec2.ssh.EC2SSHLauncher", Level.ALL);
        launcher.launch(mockEC2Computer, mockListener);
        // Test for marker doesn't exists.
        assertTrue(loggerRule.getMessages().stream()
                .anyMatch(message -> message.contains("Failed to execute remote command: test -e ~/.hudson-run-init")));
        // Test for successful init script execution.
        assertTrue(loggerRule.getMessages().stream()
                .anyMatch(message ->
                        message.contains("Init script executed successfully and creating ~/.hudson-run-init")));
        // Test for failed marker creation.
        assertTrue(loggerRule.getMessages().stream()
                .anyMatch(message -> message.contains("Unable to create ~/.hudson-run-init")));
    }

    private EC2AbstractSlave getMockNodeTemplate(String initScript) throws Exception {
        return new EC2AbstractSlave(
                "InitScriptTestInstance",
                "i-initscripttest",
                "description",
                "fs",
                1,
                null,
                "label",
                null,
                null,
                initScript,
                "/tmp",
                new ArrayList<>(),
                "remote",
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "jvm",
                false,
                "idle",
                null,
                "cloud",
                Integer.MAX_VALUE,
                null,
                null,
                -1,
                null,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED) {
            @Override
            public void terminate() {}

            @Override
            public String getEc2Type() {
                return null;
            }
        };
    }

    private void defineBehaviourCommon() throws Exception {
        // Define the mock behaviour.
        when(mockListener.getLogger()).thenReturn(mockPS);
        when(mockListener.error(any())).thenReturn(mockPW);
        when(mockEC2Computer.getNode()).thenReturn(mockNode);
        when(mockEC2Computer.getSlaveTemplate()).thenReturn(mockTemplate);
        mockStaticSSHClientHelper.when(SSHClientHelper::getInstance).thenReturn(mockSSHClientHelper);
        when(mockSSHClientHelper.setupSshClient(any())).thenReturn(mockSshClient);
        when(mockEC2Computer.getCloud()).thenReturn(mockCloud);
        when(mockCloud.getKeyPair()).thenReturn(mockKp);
        when(mockKp.getKeyPairInfo()).thenReturn(mockKPInfo);
        when(mockKPInfo.keyName()).thenReturn("initscripttest_keyname");
        when(mockKPInfo.keyFingerprint()).thenReturn("initscripttest_fingerprint");
        mockTemplate.connectionStrategy = ConnectionStrategy.PUBLIC_DNS;
        when(mockEC2Computer.updateInstanceDescription()).thenReturn(mockInstance);
        when(mockInstance.publicDnsName()).thenReturn(mockHost);
        when(mockInstance.state())
                .thenReturn(InstanceState.builder()
                        .code(16)
                        .name(InstanceStateName.RUNNING)
                        .build());
        mockStaticScpClientCreator.when(ScpClientCreator::instance).thenReturn(mockScpClientCreator);
        when(mockScpClientCreator.createScpClient(mockClientSession)).thenReturn(mockScpClient);
        mockStaticScpClientCreator
                .when(() -> CloseableScpClient.singleSessionInstance(mockScpClient))
                .thenReturn(mockClosableScpClient);
        doNothing().when(mockSshClient).setServerKeyVerifier(any());
        doNothing().when(mockSshClient).start();
        doNothing().when(mockSshClient).setClientProxyConnector(any());
        when(mockEC2Computer.getRemoteAdmin()).thenReturn(mockAdmin);
        when(mockSshClient.connect(mockAdmin, mockHost, 0)).thenReturn(mockConnectFuture);
        when(mockConnectFuture.verify(10000, TimeUnit.SECONDS)).thenReturn(mockConnectFuture);
        when(mockConnectFuture.getClientSession()).thenReturn(mockClientSession);
        when(mockKp.getMaterial()).thenReturn("initscripttest_keymaterial");
        mockStaticKeyHelper
                .when(() -> KeyHelper.decodeKeyPair("initscripttest_keymaterial", ""))
                .thenReturn(mockKeyPair);
        doNothing().when(mockClientSession).addPublicKeyIdentity(mockKeyPair);
        when(mockClientSession.auth()).thenReturn(mockAuthFuture);
        when(mockAuthFuture.await()).thenReturn(true);
        when(mockClientSession.isAuthenticated()).thenReturn(true);
        when(mockClientSession.createExecChannel(any(), any(), any(), any())).thenReturn(mockAgentChannelExec);
        when(mockAgentChannelExec.open()).thenReturn(mockOpenFuture);
        when(mockOpenFuture.verify(10000)).thenReturn(mockOpenFuture);
        String tmpDirCmd = "mkdir -p /tmp";
        doNothing().when(mockClientSession).executeRemoteCommand(tmpDirCmd, mockPS, mockPS, null);
        String markerChkCmd = "test -e ~/.hudson-run-init";
        doThrow(new IOException("Marker doesn't exists"))
                .when(mockClientSession)
                .executeRemoteCommand(markerChkCmd, mockPS, mockPS, null);
        doNothing().when(mockScpClient).upload(any(), any(), any(), any());
    }
}
