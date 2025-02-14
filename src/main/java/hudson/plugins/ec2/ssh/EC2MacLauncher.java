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

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.KeyPair;
import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.ec2.ConnectionStrategy;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2HostAddressProvider;
import hudson.plugins.ec2.EC2PrivateKey;
import hudson.plugins.ec2.EC2Readiness;
import hudson.plugins.ec2.EC2SpotSlave;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.ssh.proxy.ProxyCONNECTListener;
import hudson.plugins.ec2.ssh.verifiers.HostKey;
import hudson.plugins.ec2.ssh.verifiers.Messages;
import hudson.plugins.ec2.util.KeyHelper;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.CloseableScpClient;
import org.apache.sshd.scp.common.helpers.ScpTimestampCommandDetails;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.crypto.util.PublicKeyFactory;

/**
 * {@link ComputerLauncher} that connects to a Unix agent on EC2 by using SSH.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2MacLauncher extends EC2ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(EC2MacLauncher.class.getName());

    private static final String BOOTSTRAP_AUTH_SLEEP_MS = "jenkins.ec2.bootstrapAuthSleepMs";
    private static final String BOOTSTRAP_AUTH_TRIES = "jenkins.ec2.bootstrapAuthTries";
    private static final String READINESS_SLEEP_MS = "jenkins.ec2.readinessSleepMs";
    private static final String READINESS_TRIES = "jenkins.ec2.readinessTries";
    private static final String CORRETTO_LATEST_URL = "https://corretto.aws/downloads/latest";

    private static int bootstrapAuthSleepMs = 30000;
    private static int bootstrapAuthTries = 30;

    private static int readinessSleepMs = 1000;
    private static int readinessTries = 120;

    static {
        String prop = System.getProperty(BOOTSTRAP_AUTH_SLEEP_MS);
        if (prop != null) {
            bootstrapAuthSleepMs = Integer.parseInt(prop);
        }
        prop = System.getProperty(BOOTSTRAP_AUTH_TRIES);
        if (prop != null) {
            bootstrapAuthTries = Integer.parseInt(prop);
        }
        prop = System.getProperty(READINESS_TRIES);
        if (prop != null) {
            readinessTries = Integer.parseInt(prop);
        }
        prop = System.getProperty(READINESS_SLEEP_MS);
        if (prop != null) {
            readinessSleepMs = Integer.parseInt(prop);
        }
    }

    protected void log(Level level, EC2Computer computer, TaskListener listener, String message) {
        EC2Cloud.log(LOGGER, level, listener, message);
    }

    protected void logException(EC2Computer computer, TaskListener listener, String message, Throwable exception) {
        EC2Cloud.log(LOGGER, Level.WARNING, listener, message, exception);
    }

    protected void logInfo(EC2Computer computer, TaskListener listener, String message) {
        log(Level.INFO, computer, listener, message);
    }

    protected void logWarning(EC2Computer computer, TaskListener listener, String message) {
        log(Level.WARNING, computer, listener, message);
    }

    protected String buildUpCommand(EC2Computer computer, String command) {
        String remoteAdmin = computer.getRemoteAdmin();
        if (remoteAdmin != null && !remoteAdmin.equals("root")) {
            command = computer.getRootCommandPrefix() + " " + command;
        }
        return command;
    }

    @Override
    protected void launchScript(EC2Computer computer, TaskListener listener)
            throws IOException, AmazonClientException, InterruptedException {
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

        if (node instanceof EC2Readiness) {
            EC2Readiness readinessNode = (EC2Readiness) node;
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
                throw new AmazonClientException(
                        "Node still not ready, timed out after " + (readinessTries * readinessSleepMs / 1000)
                                + "s with status " + readinessNode.getEc2ReadinessStatus());
            }
        }

        logInfo(computer, listener, "Launching instance: " + node.getInstanceId());

        // TODO: parse the version number. maven-enforcer-plugin might help
        final String javaPath = node.javaPath;
        String tmpDir = (Util.fixEmptyAndTrim(node.tmpDir) != null ? node.tmpDir : "/tmp");

        try (SshClient client = SshClient.setUpDefaultClient()) {
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
                    clientSession.addPublicKeyIdentity(KeyHelper.decodeKeyPair(key.getKeyMaterial(), ""));
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
                        executeRemote(clientSession, initCommand, logger);

                        logInfo(computer, listener, "Creating ~/.hudson-run-init");
                        String createHudsonRunInitCommand = buildUpCommand(computer, "touch ~/.hudson-run-init");
                        executeRemote(clientSession, createHudsonRunInitCommand, logger);
                    }

                    try {
                        Instance nodeInstance = computer.describeInstance();
                        if (nodeInstance.getInstanceType().equals("mac2.metal")) {
                            LOGGER.info("Running Command for mac2.metal");
                            executeRemote(
                                    computer,
                                    clientSession,
                                    javaPath + " -fullversion",
                                    "curl -L -O "
                                            + CORRETTO_LATEST_URL
                                            + "/amazon-corretto-11-aarch64-macos-jdk.pkg; sudo installer -pkg amazon-corretto-11-aarch64-macos-jdk.pkg -target /",
                                    logger,
                                    listener);
                        } else {
                            executeRemote(
                                    computer,
                                    clientSession,
                                    javaPath + " -fullversion",
                                    "curl -L -O "
                                            + CORRETTO_LATEST_URL
                                            + "/amazon-corretto-11-x64-macos-jdk.pkg; sudo installer -pkg amazon-corretto-11-x64-macos-jdk.pkg -target /",
                                    logger,
                                    listener);
                        }
                    } catch (InterruptedException ex) {
                        LOGGER.warning(ex.getMessage());
                    }

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

        SlaveTemplate slaveTemplate = computer.getSlaveTemplate();

        if (slaveTemplate != null && slaveTemplate.isConnectBySSHProcess()) {
            File identityKeyFile = createIdentityKeyFile(computer);

            try {
                // Obviously the controller must have an installed ssh client.
                // Depending on the strategy selected on the UI, we set the StrictHostKeyChecking flag
                String sshClientLaunchString = String.format(
                        "ssh -o StrictHostKeyChecking=%s -i %s %s@%s -p %d %s",
                        slaveTemplate.getHostKeyVerificationStrategy().getSshCommandEquivalentFlag(),
                        identityKeyFile.getAbsolutePath(),
                        node.remoteAdmin,
                        getEC2HostAddress(computer, template),
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
            }
        } else {
            launchRemotingAgent(computer, listener, launchString, template, timeout, logger);
        }
    }

    private boolean executeRemote(ClientSession session, String command, OutputStream logger) {
        try {
            session.executeRemoteCommand(command, logger, logger, null);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Failed to execute remote command: " + command, e);
            return false;
        }
    }

    private void launchRemotingAgent(
            EC2Computer computer,
            TaskListener listener,
            String launchString,
            SlaveTemplate template,
            long timeout,
            PrintStream logger)
            throws InterruptedException, IOException {
        logInfo(computer, listener, "Launching remoting agent (via SSH2 Connection): " + launchString);

        final SshClient remotingClient = SshClient.setUpDefaultClient();
        final ClientSession remotingSession = connectToSsh(remotingClient, computer, listener, template);
        KeyPair key = computer.getCloud().getKeyPair();
        if (key != null) {
            remotingSession.addPublicKeyIdentity(KeyHelper.decodeKeyPair(key.getKeyMaterial(), ""));
        }
        remotingSession.auth().await(timeout);
        ChannelExec agentExecChannel = remotingSession.createExecChannel(
                launchString, StandardCharsets.US_ASCII, null, Collections.emptyMap());
        agentExecChannel.open().verify(timeout);

        InputStream invertedOut = agentExecChannel.getInvertedOut();
        OutputStream invertedIn = agentExecChannel.getInvertedIn();

        Listener channelListener = new Listener() {

            @Override
            public void onClosed(Channel channel, IOException cause) {
                try {
                    agentExecChannel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error when closing the channel", e);
                }
                try {
                    remotingSession.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error when closing the session", e);
                }
                try {
                    remotingClient.stop();
                    remotingClient.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error when closing the client", e);
                }
            }
        };

        computer.setChannel(invertedOut, invertedIn, logger, channelListener);
    }

    private boolean executeRemote(
            EC2Computer computer,
            ClientSession clientSession,
            String checkCommand,
            String command,
            PrintStream logger,
            TaskListener listener) {
        logInfo(computer, listener, "Verifying: " + checkCommand);
        if (!executeRemote(clientSession, checkCommand, logger)) {
            logInfo(computer, listener, "Installing: " + command);
            if (!executeRemote(clientSession, command, logger)) {
                logWarning(computer, listener, "Failed to install: " + command);
                return false;
            }
        }
        return true;
    }

    private File createIdentityKeyFile(EC2Computer computer) throws IOException {
        EC2PrivateKey ec2PrivateKey = computer.getCloud().resolvePrivateKey();
        String privateKey = "";
        if (ec2PrivateKey != null) {
            privateKey = ec2PrivateKey.getPrivateKey();
        }

        File tempFile = Files.createTempFile("ec2_", ".pem").toFile();

        try {
            try (FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
                    OutputStreamWriter writer = new OutputStreamWriter(fileOutputStream, StandardCharsets.UTF_8)) {
                writer.write(privateKey);
                writer.flush();
            }
            FilePath filePath = new FilePath(tempFile);
            filePath.chmod(0400); // octal file mask - readonly by owner
            return tempFile;
        } catch (Exception e) {
            if (!tempFile.delete()) {
                LOGGER.log(Level.WARNING, "Failed to delete identity key file");
            }
            throw new IOException("Error creating temporary identity key file for connecting to EC2 agent.", e);
        }
    }

    private boolean bootstrap(EC2Computer computer, TaskListener listener, SlaveTemplate template)
            throws IOException, InterruptedException, AmazonClientException {
        logInfo(computer, listener, "bootstrap()");
        final EC2AbstractSlave node = computer.getNode();
        final long timeout = node == null ? 0L : node.getLaunchTimeoutInMillis();
        ClientSession bootstrapSession = null;
        try (SshClient client = SshClient.setUpDefaultClient()) {
            int tries = bootstrapAuthTries;
            boolean isAuthenticated = false;
            logInfo(computer, listener, "Getting keypair...");
            KeyPair key = computer.getCloud().getKeyPair();
            if (key == null) {
                logWarning(computer, listener, "Could not retrieve a valid key pair.");
                return false;
            }
            logInfo(
                    computer,
                    listener,
                    String.format(
                            "Using private key %s (SHA-1 fingerprint %s)", key.getKeyName(), key.getKeyFingerprint()));
            while (tries-- > 0) {
                logInfo(computer, listener, "Authenticating as " + computer.getRemoteAdmin());
                try {
                    bootstrapSession = connectToSsh(client, computer, listener, template);
                    bootstrapSession.addPublicKeyIdentity(KeyHelper.decodeKeyPair(key.getKeyMaterial(), ""));
                    bootstrapSession.auth().await(timeout);

                    isAuthenticated = bootstrapSession.isAuthenticated();
                } catch (IOException e) {
                    logException(computer, listener, "Exception trying to authenticate", e);
                    bootstrapSession.close();
                }
                if (isAuthenticated) {
                    break;
                }
                logWarning(computer, listener, "Authentication failed. Trying again...");
                Thread.sleep(bootstrapAuthSleepMs);
            }
            if (!isAuthenticated) {
                logWarning(computer, listener, "Authentication failed");
                return false;
            }
        } finally {
            if (bootstrapSession != null) {
                bootstrapSession.close();
            }
        }
        return true;
    }

    private ClientSession connectToSsh(
            SshClient client, EC2Computer computer, TaskListener listener, SlaveTemplate template)
            throws AmazonClientException, InterruptedException {
        final EC2AbstractSlave node = computer.getNode();
        final long timeout = node == null ? 0L : node.getLaunchTimeoutInMillis();
        final long startTime = System.currentTimeMillis();
        while (true) {
            try {

                long waitTime = System.currentTimeMillis() - startTime;
                if (timeout > 0 && waitTime > timeout) {
                    throw new AmazonClientException("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for ssh to become available. (maximum timeout configured is "
                            + (timeout / 1000) + ")");
                }
                String host = getEC2HostAddress(computer, template);

                if ((node instanceof EC2SpotSlave) && computer.getInstanceId() == null) {
                    // getInstanceId() on EC2SpotSlave can return null if the spot request doesn't yet know
                    // the instance id that it is starting. Continue to wait until the instanceId is set.
                    logInfo(computer, listener, "empty instanceId for Spot Slave.");
                    throw new IOException("goto sleep");
                }

                if (StringUtils.isBlank(host)) {
                    logWarning(computer, listener, "Empty host, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                if ("0.0.0.0".equals(host)) {
                    logWarning(
                            computer,
                            listener,
                            "Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                int port = computer.getSshPort();
                Integer slaveConnectTimeout = Integer.getInteger("jenkins.ec2.slaveConnectTimeout", 10000);
                logInfo(
                        computer,
                        listener,
                        "Connecting to " + host + " on port " + port + ", with timeout " + slaveConnectTimeout + ".");

                // Configure Host key verification
                client.setServerKeyVerifier(new ServerKeyVerifierImpl(computer, listener));
                client.start();

                ConnectFuture connectFuture;

                ProxyConfiguration proxyConfig = Jenkins.get().proxy;
                Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(host);
                if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
                    InetSocketAddress address = (InetSocketAddress) proxy.address();
                    String username = proxyConfig.getUserName();
                    String password = proxyConfig.getPassword();

                    client.setClientProxyConnector(new ProxyCONNECTListener(host, port, username, password));

                    connectFuture = client.connect(computer.getRemoteAdmin(), address);

                    logInfo(computer, listener, "Using HTTP Proxy Configuration");
                } else {
                    connectFuture = client.connect(computer.getRemoteAdmin(), host, port);
                }

                ClientSession clientSession = connectFuture
                        .verify(slaveConnectTimeout, TimeUnit.SECONDS) // successfully connected
                        .getClientSession();

                logInfo(computer, listener, "Connected via SSH.");
                return clientSession;
            } catch (IOException e) {
                // keep retrying until SSH comes up
                logInfo(computer, listener, "Failed to connect via ssh: " + e.getMessage());

                // If the computer was set offline because it's not trusted, we avoid persisting in connecting to it.
                // The computer is offline for a long period
                if (computer.isOffline()
                        && StringUtils.isNotBlank(computer.getOfflineCauseReason())
                        && computer.getOfflineCauseReason().equals(Messages.OfflineCause_SSHKeyCheckFailed())) {
                    throw new AmazonClientException(
                            "The connection couldn't be established and the computer is now offline", e);
                } else {
                    logInfo(computer, listener, "Waiting for SSH to come up. Sleeping 5.");
                    Thread.sleep(5000);
                }
            }
        }
    }

    /**
     * Our host key verifier just pick up the right strategy and call its verify method.
     */
    private static class ServerKeyVerifierImpl implements ServerKeyVerifier {
        private final EC2Computer computer;
        private final TaskListener listener;

        public ServerKeyVerifierImpl(final EC2Computer computer, final TaskListener listener) {
            this.computer = computer;
            this.listener = listener;
        }

        @Override
        public boolean verifyServerKey(ClientSession clientSession, SocketAddress remoteAddress, PublicKey serverKey) {
            String sshAlgorithm = KeyHelper.getSshAlgorithm(serverKey);
            if (sshAlgorithm == null) {
                return false;
            }
            SlaveTemplate template = computer.getSlaveTemplate();
            try {
                AsymmetricKeyParameter parameters = PublicKeyFactory.createKey(serverKey.getEncoded());
                byte[] openSSHBytes = OpenSSHPublicKeyUtil.encodePublicKey(parameters);

                return template != null
                        && template.getHostKeyVerificationStrategy()
                                .getStrategy()
                                .verify(computer, new HostKey(sshAlgorithm, openSSHBytes), listener);
            } catch (Exception exception) {
                // false will trigger a SSHException which is a subclass of IOException.
                // Therefore, it is not needed to throw a RuntimeException.
                EC2Cloud.log(LOGGER, Level.WARNING, listener, "Unable to check the server key", exception);
                return false;
            }
        }
    }

    private static String getEC2HostAddress(EC2Computer computer, SlaveTemplate template) throws InterruptedException {
        Instance instance = computer.updateInstanceDescription();
        ConnectionStrategy strategy = template.connectionStrategy;
        return EC2HostAddressProvider.unix(instance, strategy);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
