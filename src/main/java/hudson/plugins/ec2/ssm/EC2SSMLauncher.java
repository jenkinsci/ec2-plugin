package hudson.plugins.ec2.ssm;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2Readiness;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.lang.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationRequest;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationResponse;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.InstanceInformationFilter;
import software.amazon.awssdk.services.ssm.model.InstanceInformationFilterKey;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;
import software.amazon.awssdk.services.ssm.model.SendCommandResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

/**
 * {@link hudson.slaves.ComputerLauncher} that connects to an EC2 instance using AWS Systems Manager (SSM)
 * instead of SSH. This eliminates the need for SSH keys.
 */
public class EC2SSMLauncher extends EC2ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(EC2SSMLauncher.class.getName());

    private static final String READINESS_SLEEP_MS_PROPERTY = "jenkins.ec2.ssm.readinessSleepMs";
    private static final String READINESS_TRIES_PROPERTY = "jenkins.ec2.ssm.readinessTries";
    private static final String COMMAND_POLL_SLEEP_MS_PROPERTY = "jenkins.ec2.ssm.commandPollSleepMs";
    private static final String COMMAND_POLL_TRIES_PROPERTY = "jenkins.ec2.ssm.commandPollTries";

    private static final int DEFAULT_READINESS_SLEEP_MS = 5000;
    private static final int DEFAULT_READINESS_TRIES = 60;
    private static final int DEFAULT_COMMAND_POLL_SLEEP_MS = 5000;
    private static final int DEFAULT_COMMAND_POLL_TRIES = 120;

    private final int readinessSleepMs;
    private final int readinessTries;
    private final int commandPollSleepMs;
    private final int commandPollTries;

    public EC2SSMLauncher() {
        this.readinessSleepMs = Integer.getInteger(READINESS_SLEEP_MS_PROPERTY, DEFAULT_READINESS_SLEEP_MS);
        this.readinessTries = Integer.getInteger(READINESS_TRIES_PROPERTY, DEFAULT_READINESS_TRIES);
        this.commandPollSleepMs = Integer.getInteger(COMMAND_POLL_SLEEP_MS_PROPERTY, DEFAULT_COMMAND_POLL_SLEEP_MS);
        this.commandPollTries = Integer.getInteger(COMMAND_POLL_TRIES_PROPERTY, DEFAULT_COMMAND_POLL_TRIES);
    }

    @Override
    protected void launchScript(EC2Computer computer, TaskListener listener)
            throws SdkException, IOException, InterruptedException {
        EC2AbstractSlave node = computer.getNode();

        if (node == null) {
            throw new IllegalStateException("Node is null");
        }

        if (computer.getSlaveTemplate() == null) {
            throw new IOException("Could not find corresponding agent template for " + computer.getDisplayName());
        }

        EC2Cloud cloud = node.getCloud();
        String instanceId = node.getInstanceId();

        // Phase A: Wait for EC2 readiness
        waitForEC2Readiness(computer, node, listener);

        logInfo(computer, listener, "Launching SSM-based agent for instance: " + instanceId);

        SsmClient ssmClient = cloud.createSsmClient();

        // Phase A (cont.): Wait for SSM agent readiness
        waitForSSMReadiness(ssmClient, instanceId, computer, listener);

        String tmpDir = Util.fixEmptyAndTrim(node.tmpDir) != null ? node.tmpDir : "/tmp";
        String javaPath = node.javaPath;
        String jvmopts = node.jvmopts;

        // Phase B: Run init script via SSM SendCommand
        runInitScript(ssmClient, instanceId, node, computer, listener);

        // Phase C: Setup remoting
        setupRemoting(ssmClient, instanceId, tmpDir, javaPath, computer, listener);

        // Phase D: Launch remoting agent via SSM SendCommand (WebSocket inbound)
        launchRemotingAgent(ssmClient, instanceId, tmpDir, javaPath, jvmopts, computer, listener);
    }

    private void waitForEC2Readiness(EC2Computer computer, EC2AbstractSlave node, TaskListener listener)
            throws InterruptedException {
        if (node instanceof EC2Readiness readinessNode) {
            int tries = readinessTries;
            while (tries-- > 0) {
                if (readinessNode.isReady()) {
                    break;
                }
                logInfo(computer, listener, "EC2 node not yet ready. Status: " + readinessNode.getEc2ReadinessStatus());
                Thread.sleep(readinessSleepMs);
            }

            if (!readinessNode.isReady()) {
                throw SdkException.builder()
                        .message("EC2 node not ready after " + (readinessTries * readinessSleepMs / 1000)
                                + "s. Status: " + readinessNode.getEc2ReadinessStatus())
                        .build();
            }
        }
    }

    private void waitForSSMReadiness(
            SsmClient ssmClient, String instanceId, EC2Computer computer, TaskListener listener)
            throws InterruptedException {
        logInfo(computer, listener, "Waiting for SSM agent to register instance: " + instanceId);

        for (int i = 0; i < readinessTries; i++) {
            try {
                DescribeInstanceInformationResponse response =
                        ssmClient.describeInstanceInformation(DescribeInstanceInformationRequest.builder()
                                .instanceInformationFilterList(InstanceInformationFilter.builder()
                                        .key(InstanceInformationFilterKey.INSTANCE_IDS)
                                        .valueSet(instanceId)
                                        .build())
                                .build());

                if (!response.instanceInformationList().isEmpty()) {
                    String pingStatus =
                            response.instanceInformationList().get(0).pingStatusAsString();
                    if ("Online".equals(pingStatus)) {
                        logInfo(computer, listener, "SSM agent is online for instance: " + instanceId);
                        return;
                    }
                    logInfo(
                            computer,
                            listener,
                            "SSM agent registered but ping status is: " + pingStatus + ". Waiting...");
                } else {
                    logInfo(
                            computer,
                            listener,
                            "SSM agent not yet registered for instance: " + instanceId + ". Attempt " + (i + 1) + "/"
                                    + readinessTries);
                }
            } catch (SsmException e) {
                LOGGER.log(Level.FINE, "SSM describe error (will retry): " + e.getMessage());
            }
            Thread.sleep(readinessSleepMs);
        }

        throw SdkException.builder()
                .message("SSM agent did not become ready for instance " + instanceId + " after "
                        + (readinessTries * readinessSleepMs / 1000) + "s")
                .build();
    }

    private void runInitScript(
            SsmClient ssmClient, String instanceId, EC2AbstractSlave node, EC2Computer computer, TaskListener listener)
            throws InterruptedException, IOException {
        String initScript = node.initScript;

        if (StringUtils.isBlank(initScript)) {
            logInfo(computer, listener, "No init script to run");
            return;
        }

        // Check if init script has already been run
        String checkResult =
                runSSMCommand(ssmClient, instanceId, "test -e ~/.hudson-run-init && echo 'EXISTS' || echo 'NOT_FOUND'");
        if (checkResult != null && checkResult.trim().contains("EXISTS")) {
            logInfo(computer, listener, "Init script already executed (marker file exists)");
            return;
        }

        logInfo(computer, listener, "Running init script via SSM SendCommand");

        String commandId = sendSSMCommand(ssmClient, instanceId, initScript, computer, listener);
        waitForCommand(ssmClient, instanceId, commandId, computer, listener);

        GetCommandInvocationResponse result = ssmClient.getCommandInvocation(GetCommandInvocationRequest.builder()
                .commandId(commandId)
                .instanceId(instanceId)
                .build());

        if (result.responseCode() != 0) {
            String output = result.standardOutputContent();
            String error = result.standardErrorContent();
            logWarning(computer, listener, "Init script failed with exit code: " + result.responseCode());
            if (StringUtils.isNotBlank(output)) {
                logInfo(computer, listener, "Init script stdout: " + output);
            }
            if (StringUtils.isNotBlank(error)) {
                logWarning(computer, listener, "Init script stderr: " + error);
            }
            throw new IOException("Init script failed with exit code: " + result.responseCode());
        }

        logInfo(computer, listener, "Init script completed successfully");

        // Set marker file
        sendSSMCommandAndWait(ssmClient, instanceId, "touch ~/.hudson-run-init", computer, listener);
    }

    private void setupRemoting(
            SsmClient ssmClient,
            String instanceId,
            String tmpDir,
            String javaPath,
            EC2Computer computer,
            TaskListener listener)
            throws InterruptedException, IOException {

        // Create tmp directory
        logInfo(computer, listener, "Creating tmp directory: " + tmpDir);
        sendSSMCommandAndWait(ssmClient, instanceId, "mkdir -p " + tmpDir, computer, listener);

        // Install Java if needed
        logInfo(computer, listener, "Checking Java availability");
        String javaCheck = javaPath + " -fullversion 2>&1 || echo 'JAVA_NOT_FOUND'";
        String javaResult = runSSMCommand(ssmClient, instanceId, javaCheck);
        if (javaResult != null && javaResult.contains("JAVA_NOT_FOUND")) {
            logInfo(computer, listener, "Java not found, attempting to install");
            sendSSMCommandAndWait(
                    ssmClient,
                    instanceId,
                    "sudo amazon-linux-extras install java-openjdk11 -y 2>/dev/null; sudo yum install -y fontconfig java-11-openjdk 2>/dev/null || true",
                    computer,
                    listener);
        }

        // Download remoting.jar
        String jenkinsUrl = JenkinsLocationConfiguration.get().getUrl();
        if (jenkinsUrl == null) {
            throw new IOException("Jenkins URL is not configured. Please set the Jenkins URL in system configuration.");
        }
        // Ensure URL ends with /
        if (!jenkinsUrl.endsWith("/")) {
            jenkinsUrl += "/";
        }

        logInfo(computer, listener, "Downloading agent.jar to " + tmpDir);
        String downloadCommand =
                "curl -sO " + jenkinsUrl + "jnlpJars/agent.jar && mv agent.jar " + tmpDir + "/agent.jar";
        sendSSMCommandAndWait(ssmClient, instanceId, downloadCommand, computer, listener);
    }

    private void launchRemotingAgent(
            SsmClient ssmClient,
            String instanceId,
            String tmpDir,
            String javaPath,
            String jvmopts,
            EC2Computer computer,
            TaskListener listener)
            throws IOException, InterruptedException {

        String jenkinsUrl = JenkinsLocationConfiguration.get().getUrl();
        if (jenkinsUrl == null) {
            throw new IOException("Jenkins URL is not configured. Please set the Jenkins URL in system configuration.");
        }
        if (!jenkinsUrl.endsWith("/")) {
            jenkinsUrl += "/";
        }

        String secret = computer.getJnlpMac();
        String nodeName = computer.getName();

        String launchCommand = String.format(
                "nohup %s %s -jar %s/agent.jar -url '%s' -secret %s -name '%s' -webSocket > %s/agent.log 2>&1 &",
                javaPath, (jvmopts != null ? jvmopts : ""), tmpDir, jenkinsUrl, secret, nodeName, tmpDir);

        logInfo(computer, listener, "Launching remoting agent via SSM SendCommand (WebSocket inbound)");
        sendSSMCommand(ssmClient, instanceId, launchCommand, computer, listener);

        // Wait for the agent to connect back via WebSocket
        logInfo(computer, listener, "Waiting for remoting agent to connect...");
        for (int i = 0; i < readinessTries; i++) {
            if (computer.getChannel() != null) {
                logInfo(computer, listener, "Remoting agent connected successfully");
                return;
            }
            Thread.sleep(readinessSleepMs);
        }
        throw new IOException(
                "Remoting agent did not connect within " + (readinessTries * readinessSleepMs / 1000) + "s");
    }

    private String sendSSMCommand(
            SsmClient ssmClient, String instanceId, String command, EC2Computer computer, TaskListener listener) {
        List<String> commands = new ArrayList<>();
        commands.add(command);

        SendCommandResponse response = ssmClient.sendCommand(SendCommandRequest.builder()
                .instanceIds(instanceId)
                .documentName("AWS-RunShellScript")
                .parameters(Map.of("commands", commands))
                .build());

        return response.command().commandId();
    }

    private void waitForCommand(
            SsmClient ssmClient, String instanceId, String commandId, EC2Computer computer, TaskListener listener)
            throws InterruptedException, IOException {
        for (int i = 0; i < commandPollTries; i++) {
            try {
                GetCommandInvocationResponse response =
                        ssmClient.getCommandInvocation(GetCommandInvocationRequest.builder()
                                .commandId(commandId)
                                .instanceId(instanceId)
                                .build());

                CommandInvocationStatus status = response.status();
                if (status == CommandInvocationStatus.SUCCESS
                        || status == CommandInvocationStatus.FAILED
                        || status == CommandInvocationStatus.TIMED_OUT
                        || status == CommandInvocationStatus.CANCELLED) {
                    return;
                }
            } catch (SsmException e) {
                // InvocationDoesNotExist means the command hasn't been received by the instance yet
                if (!"InvocationDoesNotExist".equals(e.awsErrorDetails().errorCode())) {
                    throw e;
                }
            }
            Thread.sleep(commandPollSleepMs);
        }

        throw new IOException("SSM command " + commandId + " did not complete after "
                + (commandPollTries * commandPollSleepMs / 1000) + "s");
    }

    private void sendSSMCommandAndWait(
            SsmClient ssmClient, String instanceId, String command, EC2Computer computer, TaskListener listener)
            throws InterruptedException, IOException {
        String commandId = sendSSMCommand(ssmClient, instanceId, command, computer, listener);
        waitForCommand(ssmClient, instanceId, commandId, computer, listener);
    }

    private String runSSMCommand(SsmClient ssmClient, String instanceId, String command) throws InterruptedException {
        List<String> commands = new ArrayList<>();
        commands.add(command);

        SendCommandResponse response = ssmClient.sendCommand(SendCommandRequest.builder()
                .instanceIds(instanceId)
                .documentName("AWS-RunShellScript")
                .parameters(Map.of("commands", commands))
                .build());

        String commandId = response.command().commandId();

        for (int i = 0; i < commandPollTries; i++) {
            try {
                GetCommandInvocationResponse invocation =
                        ssmClient.getCommandInvocation(GetCommandInvocationRequest.builder()
                                .commandId(commandId)
                                .instanceId(instanceId)
                                .build());

                CommandInvocationStatus status = invocation.status();
                if (status == CommandInvocationStatus.SUCCESS
                        || status == CommandInvocationStatus.FAILED
                        || status == CommandInvocationStatus.TIMED_OUT
                        || status == CommandInvocationStatus.CANCELLED) {
                    return invocation.standardOutputContent();
                }
            } catch (SsmException e) {
                if (!"InvocationDoesNotExist".equals(e.awsErrorDetails().errorCode())) {
                    return null;
                }
            }
            Thread.sleep(commandPollSleepMs);
        }
        return null;
    }

    private void logInfo(EC2Computer computer, TaskListener listener, String message) {
        EC2Cloud.log(LOGGER, Level.INFO, listener, message);
    }

    private void logWarning(EC2Computer computer, TaskListener listener, String message) {
        EC2Cloud.log(LOGGER, Level.WARNING, listener, message);
    }
}
