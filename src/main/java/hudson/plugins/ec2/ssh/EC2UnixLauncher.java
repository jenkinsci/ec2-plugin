/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2.ssh;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2Readiness;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.util.KeyHelper;
import hudson.plugins.ec2.util.KeyPair;
import hudson.plugins.ec2.util.SSHClientHelper;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
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

/**
 * {@link ComputerLauncher} that connects to a Unix agent on EC2 by using SSH.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends EC2SSHLauncher {

    private static final Logger LOGGER = Logger.getLogger(EC2UnixLauncher.class.getName());

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
        String tmpDir = (Util.fixEmptyAndTrim(node.tmpDir) != null ? node.tmpDir : "/tmp");

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

            // connect fresh as ROOT
            logInfo(computer, listener, "connect fresh as root");
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
                    return; // failed to connect as root.
                }

                try (CloseableScpClient scp = createScpClient(clientSession)) {
                    String timestamp =
                            Duration.ofMillis(System.currentTimeMillis()).toSeconds() + " 0";
                    ScpTimestampCommandDetails scpTimestamp =
                            ScpTimestampCommandDetails.parse("T" + timestamp + " " + timestamp);
                    String initScript = node.initScript;

                    logInfo(computer, listener, "Creating tmp directory (" + tmpDir + ") if it does not exist");
                    executeRemote(clientSession, "mkdir -p " + tmpDir, logger);

                    if (StringUtils.isNotBlank(initScript)
                            && !executeRemote(clientSession, "test -e ~/.hudson-run-init", logger)) {
                        logInfo(computer, listener, "Upload init script");
                        scp.upload(
                                initScript.getBytes(StandardCharsets.UTF_8),
                                tmpDir + "/init.sh",
                                List.of(
                                        PosixFilePermission.OWNER_READ,
                                        PosixFilePermission.OWNER_WRITE,
                                        PosixFilePermission.OWNER_EXECUTE),
                                scpTimestamp);

                        logInfo(computer, listener, "Executing init script");
                        String initCommand = buildUpCommand(computer, tmpDir + "/init.sh");
                        // Set the flag only when init script executed successfully.
                        if (executeRemote(clientSession, initCommand, logger)) {
                            log(
                                    Level.FINE,
                                    computer,
                                    listener,
                                    "Init script executed successfully and creating ~/.hudson-run-init");
                            String createHudsonRunInitCommand = buildUpCommand(computer, "touch ~/.hudson-run-init");
                            if (!executeRemote(clientSession, createHudsonRunInitCommand, logger)) {
                                logInfo(computer, listener, "Unable to create ~/.hudson-run-init");
                            }
                        } else {
                            log(
                                    Level.WARNING,
                                    computer,
                                    listener,
                                    "Failed to execute init script on " + node.getInstanceId());
                            clientSession.close();
                            scp.close();
                            client.stop();
                            throw new IOException("Failed to execute init script on " + node.getInstanceId());
                        }
                    }

                    executeRemote(
                            computer,
                            clientSession,
                            javaPath + " -fullversion",
                            "(command -v amazon-linux-extras >/dev/null 2>&1 && " +
                            "sudo amazon-linux-extras install java-openjdk11 -y && " +
                            "sudo yum install -y fontconfig java-11-openjdk) || " +
                            "sudo dnf install -y java-11-amazon-corretto-devel fontconfig",
                            logger,
                            listener);
                    executeRemote(
                            computer,
                            clientSession,
                            "which scp",
                            "sudo yum install -y openssh-clients",
                            logger,
                            listener);

                    // Always copy so we get the most recent remoting.jar
                    logInfo(computer, listener, "Copying remoting.jar to: " + tmpDir);
                    scp.upload(
                            Jenkins.get().getJnlpJars("remoting.jar").readFully(),
                            tmpDir + "/remoting.jar",
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
                + "/remoting.jar -workDir "
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
