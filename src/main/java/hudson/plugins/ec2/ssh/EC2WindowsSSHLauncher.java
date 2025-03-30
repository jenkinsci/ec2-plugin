package hudson.plugins.ec2.ssh;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.os.WindowsUtil;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2Readiness;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.util.KeyHelper;
import hudson.plugins.ec2.util.KeyPair;
import hudson.plugins.ec2.util.SSHClientHelper;
import hudson.slaves.CommandLauncher;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.CloseableScpClient;
import org.apache.sshd.scp.common.helpers.ScpTimestampCommandDetails;
import software.amazon.awssdk.core.exception.SdkException;

public class EC2WindowsSSHLauncher extends EC2SSHLauncher {

    private static final Logger LOGGER = Logger.getLogger(EC2WindowsSSHLauncher.class.getName());

    private static final String READINESS_SLEEP_MS = "jenkins.ec2.readinessSleepMs";
    private static final String READINESS_TRIES = "jenkins.ec2.readinessTries";

    private static int readinessSleepMs = 1000;
    private static int readinessTries = 120;

    static {
        String prop = System.getProperty(READINESS_TRIES);
        if (prop != null) {
            readinessTries = Integer.parseInt(prop);
        }
        prop = System.getProperty(READINESS_SLEEP_MS);
        if (prop != null) {
            readinessSleepMs = Integer.parseInt(prop);
        }
    }

    @Override
    protected void launchScript(EC2Computer computer, TaskListener listener)
            throws IOException, SdkException, InterruptedException {
        PrintStream logger = listener.getLogger();
        EC2AbstractSlave node = computer.getNode();
        SlaveTemplate template = computer.getSlaveTemplate();

        if (node == null) {
            throw new IllegalStateException();
        }

        final long timeout = node.getLaunchTimeoutInMillis();

        if (template == null) {
            throw new IOException("Could not find corresponding agent template for " + computer.getDisplayName());
        }

        if (node instanceof EC2Readiness readinessNode) {
            int tries = readinessTries;

            while (tries-- > 0) {
                if (readinessNode.isReady()) {
                    break;
                }

                logInfo(
                        computer,
                        listener,
                        "Node still not ready. Current status: " + readinessNode.getEc2ReadinessStatus());
                Thread.sleep(readinessSleepMs);
            }

            if (!readinessNode.isReady()) {
                throw SdkException.builder()
                        .message("Node still not ready, timed out after " + (readinessTries * readinessSleepMs / 1000)
                                + "s with status " + readinessNode.getEc2ReadinessStatus())
                        .build();
            }
        }

        logInfo(computer, listener, "Launching instance: " + node.getInstanceId());

        // TODO: parse the version number. maven-enforcer-plugin might help
        final String javaPath = node.javaPath;
        String tmpDir = (node.tmpDir != null && !node.tmpDir.isEmpty()
                ? WindowsUtil.quoteArgument(Util.ensureEndsWith(node.tmpDir, "\\"))
                : "C:\\Windows\\Temp\\");

        try (SshClient client = SSHClientHelper.getInstance().setupSshClient(computer)) {
            boolean isBootstrapped = bootstrap(computer, listener, template);
            if (!isBootstrapped) {
                logWarning(computer, listener, "bootstrapresult failed");
                return; // bootstrap closed for us.
            }
            int bootDelay = node.getBootDelay();
            if (bootDelay > 0) {
                logInfo(
                        computer,
                        listener,
                        "SSH service responded. Waiting " + bootDelay + "ms for service to stabilize");
                Thread.sleep(bootDelay);
                logInfo(computer, listener, "SSH service should have stabilized");
            }

            // connect fresh as Administrator
            logInfo(computer, listener, "connect fresh as Administrator");
            try (ClientSession clientSession = connectToSsh(client, computer, listener, template)) {
                KeyPair key = computer.getCloud().getKeyPair();

                final boolean isAuthenticated;
                if (key == null) {
                    isAuthenticated = false;
                } else {
                    clientSession.addPublicKeyIdentity(KeyHelper.decodeKeyPair(key.getMaterial(), ""));
                    clientSession.auth().await(timeout);
                    isAuthenticated = clientSession.isAuthenticated();
                }
                if (!isAuthenticated) {
                    logWarning(computer, listener, "Authentication failed");
                    return; // failed to connect as Administrator.
                }

                try (CloseableScpClient scp = createScpClient(clientSession)) {
                    String timestamp =
                            Duration.ofMillis(System.currentTimeMillis()).toSeconds() + " 0";
                    ScpTimestampCommandDetails scpTimestamp =
                            ScpTimestampCommandDetails.parse("T" + timestamp + " " + timestamp);
                    String initScript = node.initScript;

                    logInfo(computer, listener, "Creating tmp directory (" + tmpDir + ") if it does not exist");
                    executeRemote(clientSession, "IF NOT EXIST " + tmpDir + " MKDIR " + tmpDir, logger);

                    if (StringUtils.isNotBlank(initScript)
                            && !executeRemote(
                                    clientSession,
                                    "IF NOT EXIST %USERPROFILE%\\.hudson-run-init EXIT /B 999",
                                    logger)) {
                        logInfo(computer, listener, "Upload init script");
                        scp.upload(
                                initScript.getBytes(StandardCharsets.UTF_8),
                                tmpDir + "init.bat",
                                List.of(
                                        PosixFilePermission.OWNER_READ,
                                        PosixFilePermission.OWNER_WRITE,
                                        PosixFilePermission.OWNER_EXECUTE),
                                scpTimestamp);

                        logInfo(computer, listener, "Executing init script");
                        String initCommand = buildUpCommand(computer, tmpDir + "init.bat");
                        executeRemote(clientSession, initCommand, logger);

                        logInfo(computer, listener, "Creating %USERPROFILE%\\.hudson-run-init");
                        String createHudsonRunInitCommand =
                                buildUpCommand(computer, "COPY NUL %USERPROFILE%\\.hudson-run-init");
                        executeRemote(clientSession, createHudsonRunInitCommand, logger);
                    }

                    // Always copy so we get the most recent remoting.jar
                    logInfo(computer, listener, "Copying remoting.jar to: " + tmpDir);
                    scp.upload(
                            Jenkins.get().getJnlpJars("remoting.jar").readFully(),
                            tmpDir + "remoting.jar",
                            List.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                            scpTimestamp);
                }
            }
            client.stop();
        }

        final String jvmopts = node.jvmopts;
        final String prefix = computer.getSlaveCommandPrefix();
        final String suffix = computer.getSlaveCommandSuffix();
        final String remoteFS = node.getRemoteFS();
        final String workDir = Util.fixEmptyAndTrim(remoteFS) != null ? remoteFS : tmpDir;
        String launchString = prefix
                + " "
                + javaPath
                + " "
                + (jvmopts != null ? jvmopts : "")
                + " -jar "
                + tmpDir
                + "remoting.jar -workDir "
                + workDir
                + suffix;
        // launchString = launchString.trim();

        if (template.isConnectBySSHProcess()) {
            File identityKeyFile = createIdentityKeyFile(computer);
            String ec2HostAddress = getEC2HostAddress(computer, template);
            File hostKeyFile = createHostKeyFile(computer, ec2HostAddress, listener);
            String userKnownHostsFileFlag = "";
            if (hostKeyFile != null) {
                userKnownHostsFileFlag = String.format(" -o \"UserKnownHostsFile=%s\"", hostKeyFile.getAbsolutePath());
            }

            try {
                // Obviously the controller must have an installed ssh client.
                // Depending on the strategy selected on the UI, we set the StrictHostKeyChecking flag
                String sshClientLaunchString = String.format(
                        "ssh -o StrictHostKeyChecking=%s%s%s -i %s %s@%s -p %d %s",
                        template.getHostKeyVerificationStrategy().getSshCommandEquivalentFlag(),
                        userKnownHostsFileFlag,
                        getEC2HostKeyAlgorithmFlag(computer),
                        identityKeyFile.getAbsolutePath(),
                        node.remoteAdmin,
                        ec2HostAddress,
                        node.getSshPort(),
                        launchString);

                logInfo(
                        computer,
                        listener,
                        "Launching remoting agent (via SSH client process): " + sshClientLaunchString);
                CommandLauncher commandLauncher = new CommandLauncher(sshClientLaunchString, null);
                commandLauncher.launch(computer, listener);
            } finally {
                if (!identityKeyFile.delete()) {
                    LOGGER.log(Level.WARNING, "Failed to delete identity key file");
                }
                if (hostKeyFile != null && !hostKeyFile.delete()) {
                    LOGGER.log(Level.WARNING, "Failed to delete host key file");
                }
            }
        } else {
            launchRemotingAgent(computer, listener, launchString, template, timeout, logger);
        }
    }
}
