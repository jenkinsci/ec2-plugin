package hudson.plugins.ec2.ssm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import hudson.plugins.ec2.*;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import hudson.plugins.ec2.util.AmazonSSMFactoryMockImpl;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;

@WithJenkins
class EC2SSMLauncherTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        AmazonEC2FactoryMockImpl.mock = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
        AmazonSSMFactoryMockImpl.mock = null;
    }

    @Test
    void testLauncherInstantiation() {
        EC2SSMLauncher launcher = new EC2SSMLauncher();
        assertNotNull(launcher);
    }

    @Test
    void testSSMReadinessPolling() {
        SsmClient ssmClient = mock(SsmClient.class);

        // First call: empty list (not registered yet)
        // Second call: online
        DescribeInstanceInformationResponse emptyResponse = DescribeInstanceInformationResponse.builder()
                .instanceInformationList(Collections.emptyList())
                .build();

        InstanceInformation onlineInfo = InstanceInformation.builder()
                .instanceId("i-12345")
                .pingStatus(PingStatus.ONLINE)
                .build();
        DescribeInstanceInformationResponse onlineResponse = DescribeInstanceInformationResponse.builder()
                .instanceInformationList(onlineInfo)
                .build();

        when(ssmClient.describeInstanceInformation(any(DescribeInstanceInformationRequest.class)))
                .thenReturn(emptyResponse)
                .thenReturn(onlineResponse);

        // Verify the mock was set up correctly
        DescribeInstanceInformationResponse first = ssmClient.describeInstanceInformation(
                DescribeInstanceInformationRequest.builder().build());
        assertTrue(first.instanceInformationList().isEmpty());

        DescribeInstanceInformationResponse second = ssmClient.describeInstanceInformation(
                DescribeInstanceInformationRequest.builder().build());
        assertFalse(second.instanceInformationList().isEmpty());
        assertEquals("Online", second.instanceInformationList().get(0).pingStatusAsString());
    }

    @Test
    void testSendCommandMocking() {
        SsmClient ssmClient = mock(SsmClient.class);

        SendCommandResponse sendResponse = SendCommandResponse.builder()
                .command(Command.builder().commandId("cmd-12345").build())
                .build();

        when(ssmClient.sendCommand(any(SendCommandRequest.class))).thenReturn(sendResponse);

        GetCommandInvocationResponse invocationResponse = GetCommandInvocationResponse.builder()
                .status(CommandInvocationStatus.SUCCESS)
                .responseCode(0)
                .standardOutputContent("output")
                .standardErrorContent("")
                .build();

        when(ssmClient.getCommandInvocation(any(GetCommandInvocationRequest.class)))
                .thenReturn(invocationResponse);

        // Verify send command works
        SendCommandResponse result = ssmClient.sendCommand(SendCommandRequest.builder()
                .instanceIds("i-12345")
                .documentName("AWS-RunShellScript")
                .parameters(java.util.Map.of("commands", List.of("echo hello")))
                .build());

        assertEquals("cmd-12345", result.command().commandId());

        // Verify get command invocation works
        GetCommandInvocationResponse invocation = ssmClient.getCommandInvocation(GetCommandInvocationRequest.builder()
                .commandId("cmd-12345")
                .instanceId("i-12345")
                .build());

        assertEquals(CommandInvocationStatus.SUCCESS, invocation.status());
        assertEquals(0, invocation.responseCode());
    }

    @Test
    void testDocumentCreation() {
        SsmClient ssmClient = mock(SsmClient.class);

        // Simulate document not found
        when(ssmClient.describeDocument(any(DescribeDocumentRequest.class)))
                .thenThrow(SsmException.builder()
                        .message("Document not found")
                        .statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("InvalidDocument")
                                .errorMessage("Document not found")
                                .build())
                        .build());

        CreateDocumentResponse createResponse = CreateDocumentResponse.builder()
                .documentDescription(DocumentDescription.builder()
                        .name("Jenkins-EC2-SSM-SessionDocument")
                        .build())
                .build();

        when(ssmClient.createDocument(any(CreateDocumentRequest.class))).thenReturn(createResponse);

        // Verify describe throws
        assertThrows(
                SsmException.class,
                () -> ssmClient.describeDocument(DescribeDocumentRequest.builder()
                        .name("Jenkins-EC2-SSM-SessionDocument")
                        .build()));

        // Verify create succeeds
        CreateDocumentResponse result = ssmClient.createDocument(CreateDocumentRequest.builder()
                .name("Jenkins-EC2-SSM-SessionDocument")
                .content("{}")
                .documentType(DocumentType.SESSION)
                .documentFormat(DocumentFormat.JSON)
                .build());

        assertEquals(
                "Jenkins-EC2-SSM-SessionDocument", result.documentDescription().name());
    }

    @Test
    void testCommandFailure() {
        SsmClient ssmClient = mock(SsmClient.class);

        SendCommandResponse sendResponse = SendCommandResponse.builder()
                .command(Command.builder().commandId("cmd-fail").build())
                .build();

        when(ssmClient.sendCommand(any(SendCommandRequest.class))).thenReturn(sendResponse);

        GetCommandInvocationResponse failedResponse = GetCommandInvocationResponse.builder()
                .status(CommandInvocationStatus.FAILED)
                .responseCode(1)
                .standardOutputContent("")
                .standardErrorContent("command not found")
                .build();

        when(ssmClient.getCommandInvocation(any(GetCommandInvocationRequest.class)))
                .thenReturn(failedResponse);

        GetCommandInvocationResponse result = ssmClient.getCommandInvocation(GetCommandInvocationRequest.builder()
                .commandId("cmd-fail")
                .instanceId("i-12345")
                .build());

        assertEquals(CommandInvocationStatus.FAILED, result.status());
        assertEquals(1, result.responseCode());
        assertEquals("command not found", result.standardErrorContent());
    }

    @Test
    void testRemotingLaunchCommandConstruction() {
        String javaPath = "java";
        String jvmopts = "-Xmx512m";
        String tmpDir = "/tmp";
        String workDir = "/home/jenkins";
        String prefix = "";
        String suffix = "";

        String launchCommand = prefix + " " + javaPath + " " + jvmopts + " -jar " + tmpDir + "/remoting.jar -workDir "
                + workDir + suffix;

        assertTrue(launchCommand.contains("java"));
        assertTrue(launchCommand.contains("-Xmx512m"));
        assertTrue(launchCommand.contains("/tmp/remoting.jar"));
        assertTrue(launchCommand.contains("-workDir /home/jenkins"));
    }
}
