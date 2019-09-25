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

import hudson.FilePath;
import hudson.Util;
import hudson.ProxyConfiguration;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.ec2.*;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.apache.commons.io.IOUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.KeyPair;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.HTTPProxyData;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;

/**
 * {@link ComputerLauncher} that connects to a Unix slave on EC2 by using SSH.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends EC2ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(EC2UnixLauncher.class.getName());

    private static final String BOOTSTRAP_AUTH_SLEEP_MS = "jenkins.ec2.bootstrapAuthSleepMs";
    private static final String BOOTSTRAP_AUTH_TRIES= "jenkins.ec2.bootstrapAuthTries";
    private static final String READINESS_SLEEP_MS = "jenkins.ec2.readinessSleepMs";
    private static final String READINESS_TRIES= "jenkins.ec2.readinessTries";

    private static int bootstrapAuthSleepMs = 30000;
    private static int bootstrapAuthTries = 30;

    private static int readinessSleepMs = 1000;
    private static int readinessTries = 120;

    static  {
        String prop = System.getProperty(BOOTSTRAP_AUTH_SLEEP_MS);
        if (prop != null)
            bootstrapAuthSleepMs = Integer.parseInt(prop);
        prop = System.getProperty(BOOTSTRAP_AUTH_TRIES);
        if (prop != null)
            bootstrapAuthTries = Integer.parseInt(prop);
        prop = System.getProperty(READINESS_TRIES);
        if (prop != null)
            readinessTries = Integer.parseInt(prop);
        prop = System.getProperty(READINESS_SLEEP_MS);
        if (prop != null)
            readinessSleepMs = Integer.parseInt(prop);
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
    protected void launchScript(EC2Computer computer, TaskListener listener) throws IOException,
            AmazonClientException, InterruptedException {
        final Connection conn;
        Connection cleanupConn = null; // java's code path analysis for final
                                       // doesn't work that well.
        boolean successful = false;
        PrintStream logger = listener.getLogger();
        EC2AbstractSlave node = computer.getNode();

        if(node == null) {
            throw new IllegalStateException();
        }

        if (node instanceof EC2Readiness) {
            EC2Readiness readinessNode = (EC2Readiness) node;
            int tries = readinessTries;

            while (tries-- > 0) {
                if (readinessNode.isReady()) {
                    break;
                }

                logInfo(computer, listener, "Node still not ready. Current status: " + readinessNode.getEc2ReadinessStatus());
                Thread.sleep(readinessSleepMs);
            }

            if (!readinessNode.isReady()) {
                throw new AmazonClientException("Node still not ready, timed out after " + (readinessTries * readinessSleepMs / 1000) + "s with status " + readinessNode.getEc2ReadinessStatus());
            }
        }

        logInfo(computer, listener, "Launching instance: " + node.getInstanceId());

        try {
            boolean isBootstrapped = bootstrap(computer, listener);
            if (isBootstrapped) {
                // connect fresh as ROOT
                logInfo(computer, listener, "connect fresh as root");
                cleanupConn = connectToSsh(computer, listener);
                KeyPair key = computer.getCloud().getKeyPair();
                if (!cleanupConn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getKeyMaterial().toCharArray(), "")) {
                    logWarning(computer, listener, "Authentication failed");
                    return; // failed to connect as root.
                }
            } else {
                logWarning(computer, listener, "bootstrapresult failed");
                return; // bootstrap closed for us.
            }
            conn = cleanupConn;

            SCPClient scp = conn.createSCPClient();
            String initScript = node.initScript;
            String tmpDir = (Util.fixEmptyAndTrim(node.tmpDir) != null ? node.tmpDir : "/tmp");

            logInfo(computer, listener, "Creating tmp directory (" + tmpDir + ") if it does not exist");
            conn.exec("mkdir -p " + tmpDir, logger);

            if (initScript != null && initScript.trim().length() > 0
                    && conn.exec("test -e ~/.hudson-run-init", logger) != 0) {
                logInfo(computer, listener, "Executing init script");
                scp.put(initScript.getBytes("UTF-8"), "init.sh", tmpDir, "0700");
                Session sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout
                                       // and stderr
                sess.execCommand(buildUpCommand(computer, tmpDir + "/init.sh"));

                sess.getStdin().close(); // nothing to write here
                sess.getStderr().close(); // we are not supposed to get anything
                                          // from stderr
                IOUtils.copy(sess.getStdout(), logger);

                int exitStatus = waitCompletion(sess);
                if (exitStatus != 0) {
                    logWarning(computer, listener, "init script failed: exit code=" + exitStatus);
                    return;
                }
                sess.close();

                logInfo(computer, listener, "Creating ~/.hudson-run-init");

                // Needs a tty to run sudo.
                sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout
                                       // and stderr
                sess.execCommand(buildUpCommand(computer, "touch ~/.hudson-run-init"));

                sess.getStdin().close(); // nothing to write here
                sess.getStderr().close(); // we are not supposed to get anything
                                          // from stderr
                IOUtils.copy(sess.getStdout(), logger);

                exitStatus = waitCompletion(sess);
                if (exitStatus != 0) {
                    logWarning(computer, listener, "init script failed: exit code=" + exitStatus);
                    return;
                }
                sess.close();
            }

            // TODO: parse the version number. maven-enforcer-plugin might help
            executeRemote(computer, conn, "java -fullversion", "sudo yum install -y java-1.8.0-openjdk.x86_64", logger, listener);
            executeRemote(computer, conn, "which scp", "sudo yum install -y openssh-clients", logger, listener);

            // Always copy so we get the most recent slave.jar
            logInfo(computer, listener, "Copying remoting.jar to: " + tmpDir);
            scp.put(Jenkins.get().getJnlpJars("remoting.jar").readFully(), "remoting.jar", tmpDir);

            final String jvmopts = node.jvmopts;
            final String prefix = computer.getSlaveCommandPrefix();
            final String suffix = computer.getSlaveCommandSuffix();
            final String remoteFS = node.getRemoteFS();
            final String workDir = Util.fixEmptyAndTrim(remoteFS) != null ? remoteFS : tmpDir;
            String launchString = prefix + " java " + (jvmopts != null ? jvmopts : "") + " -jar " + tmpDir + "/remoting.jar -workDir " + workDir + suffix;
           // launchString = launchString.trim();

            SlaveTemplate slaveTemplate = computer.getSlaveTemplate();

            if (slaveTemplate != null && slaveTemplate.isConnectBySSHProcess()) {
                File identityKeyFile = createIdentityKeyFile(computer);

                try {
                    // Obviously the master must have an installed ssh client.
                    String sshClientLaunchString = String.format("ssh -o StrictHostKeyChecking=no -i %s %s@%s -p %d %s", identityKeyFile.getAbsolutePath(), node.remoteAdmin, getEC2HostAddress(computer), node.getSshPort(), launchString);

                    logInfo(computer, listener, "Launching remoting agent (via SSH client process): " + sshClientLaunchString);
                    CommandLauncher commandLauncher = new CommandLauncher(sshClientLaunchString, null);
                    commandLauncher.launch(computer, listener);
                } finally {
                    if(!identityKeyFile.delete()) {
                        LOGGER.log(Level.WARNING, "Failed to delete identity key file");
                    }
                }
            } else {
                logInfo(computer, listener, "Launching remoting agent (via Trilead SSH2 Connection): " + launchString);
                final Session sess = conn.openSession();
                sess.execCommand(launchString);
                computer.setChannel(sess.getStdout(), sess.getStdin(), logger, new Listener() {
                    @Override
                    public void onClosed(Channel channel, IOException cause) {
                        sess.close();
                        conn.close();
                    }
                });
            }

            successful = true;
        } finally {
            if (cleanupConn != null && !successful)
                cleanupConn.close();
        }
    }

    private boolean executeRemote(EC2Computer computer, Connection conn, String checkCommand,  String command, PrintStream logger, TaskListener listener)
            throws IOException, InterruptedException {
        logInfo(computer, listener,"Verifying: " + checkCommand);
        if (conn.exec(checkCommand, logger) != 0) {
            logInfo(computer, listener, "Installing: " + command);
            if (conn.exec(command, logger) != 0) {
                logWarning(computer, listener, "Failed to install: " + command);
                return false;
            }
        }
        return true;
    }

    private File createIdentityKeyFile(EC2Computer computer) throws IOException {
        String privateKey = computer.getCloud().getPrivateKey().getPrivateKey();
        File tempFile = File.createTempFile("ec2_", ".pem");

        try {
            FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
            OutputStreamWriter writer = new OutputStreamWriter(fileOutputStream, StandardCharsets.UTF_8);
            try {
                writer.write(privateKey);
                writer.flush();
            } finally {
                writer.close();
                fileOutputStream.close();
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

    private boolean bootstrap(EC2Computer computer, TaskListener listener) throws IOException,
            InterruptedException, AmazonClientException {
        logInfo(computer, listener, "bootstrap()");
        Connection bootstrapConn = null;
        try {
            int tries = bootstrapAuthTries;
            boolean isAuthenticated = false;
            logInfo(computer, listener, "Getting keypair...");
            KeyPair key = computer.getCloud().getKeyPair();
            logInfo(computer, listener,
                String.format("Using private key %s (SHA-1 fingerprint %s)", key.getKeyName(), key.getKeyFingerprint()));
            while (tries-- > 0) {
                logInfo(computer, listener, "Authenticating as " + computer.getRemoteAdmin());
                try {
                    bootstrapConn = connectToSsh(computer, listener);
                    isAuthenticated = bootstrapConn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getKeyMaterial().toCharArray(), "");
                } catch(IOException e) {
                    logException(computer, listener, "Exception trying to authenticate", e);
                    bootstrapConn.close();
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
            if (bootstrapConn != null) {
                bootstrapConn.close();
            }
        }
        return true;
    }

    private Connection connectToSsh(EC2Computer computer, TaskListener listener) throws AmazonClientException,
            InterruptedException {
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
                String host = getEC2HostAddress(computer);

                if ((node instanceof EC2SpotSlave) && computer.getInstanceId() == null) {
                     // getInstanceId() on EC2SpotSlave can return null if the spot request doesn't yet know
                     // the instance id that it is starting. Continue to wait until the instanceId is set.
                    logInfo(computer, listener, "empty instanceId for Spot Slave.");
                    throw new IOException("goto sleep");
                }

                if ("0.0.0.0".equals(host)) {
                    logWarning(computer, listener, "Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                int port = computer.getSshPort();
                Integer slaveConnectTimeout = Integer.getInteger("jenkins.ec2.slaveConnectTimeout", 10000);
                logInfo(computer, listener, "Connecting to " + host + " on port " + port + ", with timeout " + slaveConnectTimeout
                        + ".");
                Connection conn = new Connection(host, port);
                ProxyConfiguration proxyConfig = Jenkins.get().proxy;
                Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(host);
                if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
                    InetSocketAddress address = (InetSocketAddress) proxy.address();
                    HTTPProxyData proxyData = null;
                    if (null != proxyConfig.getUserName()) {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort(), proxyConfig.getUserName(), proxyConfig.getPassword());
                    } else {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort());
                    }
                    conn.setProxyData(proxyData);
                    logInfo(computer, listener, "Using HTTP Proxy Configuration");
                }
                // currently OpenSolaris offers no way of verifying the host
                // certificate, so just accept it blindly,
                // hoping that no man-in-the-middle attack is going on.
                conn.connect(new ServerHostKeyVerifier() {
                    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey)
                            throws Exception {
                        return true;
                    }
                }, slaveConnectTimeout, slaveConnectTimeout);
                logInfo(computer, listener, "Connected via SSH.");
                return conn; // successfully connected
            } catch (IOException e) {
                // keep retrying until SSH comes up
                logInfo(computer, listener, "Failed to connect via ssh: " + e.getMessage());
                logInfo(computer, listener, "Waiting for SSH to come up. Sleeping 5.");
                Thread.sleep(5000);
            }
        }
    }

    private String getEC2HostAddress(EC2Computer computer) throws InterruptedException {
        Instance instance = computer.updateInstanceDescription();
        ConnectionStrategy strategy = computer.getSlaveTemplate().connectionStrategy;
        return EC2HostAddressProvider.unix(instance, strategy);
    }

    private int waitCompletion(Session session) throws InterruptedException {
        // I noticed that the exit status delivery often gets delayed. Wait up
        // to 1 sec.
        for (int i = 0; i < 10; i++) {
            Integer r = session.getExitStatus();
            if (r != null)
                return r;
            Thread.sleep(100);
        }
        return -1;
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
