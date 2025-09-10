package hudson.plugins.ec2.ssh;

import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.ec2.ConnectionStrategy;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2HostAddressProvider;
import hudson.plugins.ec2.EC2PrivateKey;
import hudson.plugins.ec2.EC2SpotSlave;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.ssh.proxy.ProxyCONNECTListener;
import hudson.plugins.ec2.ssh.verifiers.HostKey;
import hudson.plugins.ec2.ssh.verifiers.HostKeyHelper;
import hudson.plugins.ec2.ssh.verifiers.Messages;
import hudson.plugins.ec2.util.KeyHelper;
import hudson.plugins.ec2.util.KeyPair;
import hudson.plugins.ec2.util.SSHClientHelper;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
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
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;
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
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;

public abstract class EC2SSHLauncher extends EC2ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(EC2SSHLauncher.class.getName());

    private static final String BOOTSTRAP_AUTH_SLEEP_MS = "jenkins.ec2.bootstrapAuthSleepMs";
    private static final String BOOTSTRAP_AUTH_TRIES = "jenkins.ec2.bootstrapAuthTries";

    private static int bootstrapAuthSleepMs = 30000;
    private static int bootstrapAuthTries = 30;

    static {
        String prop = System.getProperty(BOOTSTRAP_AUTH_SLEEP_MS);
        if (prop != null) {
            bootstrapAuthSleepMs = Integer.parseInt(prop);
        }
        prop = System.getProperty(BOOTSTRAP_AUTH_TRIES);
        if (prop != null) {
            bootstrapAuthTries = Integer.parseInt(prop);
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
        String defaultAdmin = "root";
        SlaveTemplate template = computer.getSlaveTemplate();
        if (template != null && template.isWindowsSlave()) {
            defaultAdmin = "Administrator";
        }
        String remoteAdmin = computer.getRemoteAdmin();
        if (remoteAdmin != null && !remoteAdmin.equals(defaultAdmin)) {
            command = computer.getRootCommandPrefix() + " " + command;
        }
        return command;
    }

    protected void launchRemotingAgent(
            EC2Computer computer,
            TaskListener listener,
            String launchString,
            SlaveTemplate template,
            long timeout,
            PrintStream logger)
            throws InterruptedException, IOException {
        logInfo(computer, listener, "Launching remoting agent (via SSH2 Connection): " + launchString);

        final SshClient remotingClient = SSHClientHelper.getInstance().setupSshClient(computer);
        final ClientSession remotingSession = connectToSsh(remotingClient, computer, listener, template);
        KeyPair key = computer.getCloud().getKeyPair();
        if (key != null) {
            remotingSession.addPublicKeyIdentity(KeyHelper.decodeKeyPair(key.getMaterial(), ""));
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

    protected boolean executeRemote(
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

    protected boolean executeRemote(ClientSession session, String command, OutputStream logger) {
        try {
            session.executeRemoteCommand(command, logger, logger, null);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Failed to execute remote command: " + command, e);
            return false;
        }
    }

    protected File createIdentityKeyFile(EC2Computer computer) throws IOException {
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

    protected File createHostKeyFile(EC2Computer computer, String ec2HostAddress, TaskListener listener)
            throws IOException {
        HostKey ec2HostKey = HostKeyHelper.getInstance().getHostKey(computer);
        if (ec2HostKey == null) {
            return null;
        }
        File tempFile = Files.createTempFile("ec2_", "_known_hosts").toFile();
        String knownHost = "";
        knownHost = String.format(
                "%s %s %s",
                ec2HostAddress, ec2HostKey.getAlgorithm(), Base64.getEncoder().encodeToString(ec2HostKey.getKey()));

        try (FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
                OutputStreamWriter writer = new OutputStreamWriter(fileOutputStream, StandardCharsets.UTF_8)) {
            writer.write(knownHost);
            writer.flush();
            FilePath filePath = new FilePath(tempFile);
            filePath.chmod(0400); // octal file mask - readonly by owner
            return tempFile;
        } catch (Exception e) {
            if (!tempFile.delete()) {
                LOGGER.log(Level.WARNING, "Failed to delete known hosts key file");
            }
            throw new IOException("Error creating temporary known hosts file for connecting to EC2 agent.", e);
        }
    }

    protected boolean bootstrap(EC2Computer computer, TaskListener listener, SlaveTemplate template)
            throws IOException, InterruptedException, SdkException {
        logInfo(computer, listener, "bootstrap()");
        final EC2AbstractSlave node = computer.getNode();
        final long timeout = node == null ? 0L : node.getLaunchTimeoutInMillis();
        ClientSession bootstrapSession = null;
        try (SshClient client = SSHClientHelper.getInstance().setupSshClient(computer)) {
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
                            "Using private key %s (SHA-1 fingerprint %s)",
                            key.getKeyPairInfo().keyName(), key.getKeyPairInfo().keyFingerprint()));
            while (tries-- > 0) {
                logInfo(computer, listener, "Authenticating as " + computer.getRemoteAdmin());
                try {
                    bootstrapSession = connectToSsh(client, computer, listener, template);
                    bootstrapSession.addPublicKeyIdentity(KeyHelper.decodeKeyPair(key.getMaterial(), ""));
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

    protected ClientSession connectToSsh(
            SshClient client, EC2Computer computer, TaskListener listener, SlaveTemplate template)
            throws SdkException, InterruptedException {
        final EC2AbstractSlave node = computer.getNode();
        final long timeout = node == null ? 0L : node.getLaunchTimeoutInMillis();
        final long startTime = System.currentTimeMillis();
        while (true) {
            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if (timeout > 0 && waitTime > timeout) {
                    throw SdkException.builder()
                            .message("Timed out after " + (waitTime / 1000)
                                    + " seconds of waiting for ssh to become available. (maximum timeout configured is "
                                    + (timeout / 1000) + ")")
                            .build();
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
                if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress address) {
                    String username = proxyConfig.getUserName();
                    String password = proxyConfig.getSecretPassword().getPlainText();

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
                    throw SdkException.create(
                            "The connection couldn't be established and the computer is now offline", e);
                } else {
                    logInfo(computer, listener, "Waiting for SSH to come up. Sleeping 5.");
                    Thread.sleep(5000);
                }
            }
        }
    }

    @Restricted(NoExternalUse.class)
    public static class ServerKeyVerifierImpl implements ServerKeyVerifier {
        private final EC2Computer computer;
        private final TaskListener listener;

        public ServerKeyVerifierImpl(final EC2Computer computer, final TaskListener listener) {
            this.computer = computer;
            this.listener = listener;
        }

        @Override
        public boolean verifyServerKey(ClientSession clientSession, SocketAddress remoteAddress, PublicKey serverKey) {
            PublicKey usableKey = serverKey;
            // Unwrap OpenSSH certificate key into actual public key
            if (serverKey instanceof OpenSshCertificate cert) {
                // Extract actual signed public key
                usableKey = cert.getCertPubKey();
            }
            SlaveTemplate template = computer.getSlaveTemplate();
            try {
                return template != null
                        && template.getHostKeyVerificationStrategy()
                                .getStrategy()
                                .verify(computer, usableKey, listener);
            } catch (Exception exception) {
                // false will trigger a SSHException which is a subclass of IOException.
                // Therefore, it is not needed to throw a RuntimeException.
                EC2Cloud.log(LOGGER, Level.WARNING, listener, "Unable to check the server key", exception);
                return false;
            }
        }
    }

    protected static String getEC2HostAddress(EC2Computer computer, SlaveTemplate template)
            throws SdkException, InterruptedException {
        Instance instance = computer.updateInstanceDescription();
        if (instance.state().name() == InstanceStateName.TERMINATED) {
            throw SdkException.builder()
                    .message("Instance " + instance.instanceId() + " is already terminated")
                    .build();
        }
        ConnectionStrategy strategy = template.connectionStrategy;
        return template.isMacAgent()
                ? EC2HostAddressProvider.mac(instance, strategy)
                : (template.isWindowsSlave()
                        ? EC2HostAddressProvider.windows(instance, strategy)
                        : EC2HostAddressProvider.unix(instance, strategy));
    }

    protected static String getEC2HostKeyAlgorithmFlag(EC2Computer computer) throws IOException {
        HostKey ec2HostKey = HostKeyHelper.getInstance().getHostKey(computer);
        if (ec2HostKey != null) {
            return String.format(" -o \"HostKeyAlgorithms=%s\"", ec2HostKey.getAlgorithm());
        }
        return "";
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
