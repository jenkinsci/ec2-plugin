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
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
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
import java.net.URL;
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

    private static int bootstrapAuthSleepMs = 30000;
    private static int bootstrapAuthTries = 30;

    static  {
        String prop = System.getProperty(BOOTSTRAP_AUTH_SLEEP_MS);
        if (prop != null)
            bootstrapAuthSleepMs = Integer.parseInt(prop);
        prop = System.getProperty(BOOTSTRAP_AUTH_TRIES);
        if (prop != null)
            bootstrapAuthTries = Integer.parseInt(prop);
    }

    private final int FAILED = -1;

    protected void log(Level level, EC2Computer computer, TaskListener listener, String message) {
        EC2Cloud cloud = computer.getCloud();
        if (cloud != null)
            cloud.log(LOGGER, level, listener, message);
    }

    protected void logException(EC2Computer computer, TaskListener listener, String message, Throwable exception) {
        EC2Cloud cloud = computer.getCloud();
        if (cloud != null)
            cloud.log(LOGGER, Level.WARNING, listener, message, exception);
    }

    protected void logInfo(EC2Computer computer, TaskListener listener, String message) {
        log(Level.INFO, computer, listener, message);
    }

    protected void logWarning(EC2Computer computer, TaskListener listener, String message) {
        log(Level.WARNING, computer, listener, message);
    }

    protected String buildUpCommand(EC2Computer computer, String command) {
        if (!computer.getRemoteAdmin().equals("root")) {
            command = computer.getRootCommandPrefix() + " " + command;
        }
        return command;
    }

    @Override
    protected void launch(EC2Computer computer, TaskListener listener, Instance inst) throws IOException,
            AmazonClientException, InterruptedException {
        final Connection bootstrapConn;
        final Connection conn;
        Connection cleanupConn = null; // java's code path analysis for final
                                       // doesn't work that well.
        boolean successful = false;
        PrintStream logger = listener.getLogger();
        logInfo(computer, listener, "Launching instance: " + computer.getNode().getInstanceId());

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
            String initScript = computer.getNode().initScript;
            String tmpDir = (Util.fixEmptyAndTrim(computer.getNode().tmpDir) != null ? computer.getNode().tmpDir
                    : "/tmp");

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

                // Needs a tty to run sudo.
                sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout
                                       // and stderr
                sess.execCommand(buildUpCommand(computer, "touch ~/.hudson-run-init"));
                sess.close();
            }

            // TODO: parse the version number. maven-enforcer-plugin might help
            executeRemote(computer, conn, "java -fullversion", "sudo yum install -y java-1.8.0-openjdk.x86_64", logger, listener);
            executeRemote(computer, conn, "which scp", "sudo yum install -y openssh-clients", logger, listener);

            // Always copy so we get the most recent slave.jar
            logInfo(computer, listener, "Copying slave.jar to: " + tmpDir);
            scp.put(Jenkins.getInstance().getJnlpJars("slave.jar").readFully(), "slave.jar", tmpDir);

            String jvmopts = computer.getNode().jvmopts;
            String prefix = computer.getSlaveCommandPrefix();
            String launchString = prefix + " java " + (jvmopts != null ? jvmopts : "") + " -jar " + tmpDir + "/slave.jar";
           // launchString = launchString.trim();

            SlaveTemplate slaveTemplate = computer.getSlaveTemplate();

            if (slaveTemplate != null && slaveTemplate.isConnectBySSHProcess()) {
                EC2AbstractSlave node = computer.getNode();
                File identityKeyFile = createIdentityKeyFile(computer);

                try {
                    // Obviously the master must have an installed ssh client.
                    String sshClientLaunchString = String.format("ssh -o StrictHostKeyChecking=no -i %s %s@%s -p %d %s", identityKeyFile.getAbsolutePath(), node.remoteAdmin, getEC2HostAddress(computer, inst), node.getSshPort(), launchString);

                    logInfo(computer, listener, "Launching slave agent (via SSH client process): " + sshClientLaunchString);
                    CommandLauncher commandLauncher = new CommandLauncher(sshClientLaunchString, null);
                    commandLauncher.launch(computer, listener);
                } finally {
                    identityKeyFile.delete();
                }
            } else {
                logInfo(computer, listener, "Launching slave agent (via Trilead SSH2 Connection): " + launchString);
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
            tempFile.delete();
            throw new IOException("Error creating temporary identity key file for connecting to EC2 slave.", e);
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
            logInfo(computer, listener, "Using key: " + key.getKeyName() + "\n" + key.getKeyFingerprint() + "\n"
                    + key.getKeyMaterial().substring(0, 160));
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
        final long timeout = computer.getNode().getLaunchTimeoutInMillis();
        final long startTime = System.currentTimeMillis();
        while (true) {
            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if (timeout > 0 && waitTime > timeout) {
                    throw new AmazonClientException("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for ssh to become available. (maximum timeout configured is "
                            + (timeout / 1000) + ")");
                }
                Instance instance = computer.updateInstanceDescription();
                String host = getEC2HostAddress(computer, instance);

                if ("0.0.0.0".equals(host)) {
                    logWarning(computer, listener, "Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                int port = computer.getSshPort();
                Integer slaveConnectTimeout = Integer.getInteger("jenkins.ec2.slaveConnectTimeout", 10000);
                logInfo(computer, listener, "Connecting to " + host + " on port " + port + ", with timeout " + slaveConnectTimeout
                        + ".");
                Connection conn = new Connection(host, port);
                ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
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

    private String getEC2HostAddress(EC2Computer computer, Instance inst) {
        if (computer.getNode().usePrivateDnsName) {
            return inst.getPrivateDnsName();
        } else {
            String host = inst.getPublicDnsName();
            // If we fail to get a public DNS name, try to get the public IP
            // (but only if the plugin config let us use the public IP to
            // connect to the slave).
            if (host == null || host.equals("")) {
                SlaveTemplate slaveTemplate = computer.getSlaveTemplate();
                if (inst.getPublicIpAddress() != null && slaveTemplate.isConnectUsingPublicIp()) {
                    host = inst.getPublicIpAddress();
                }
            }
            // If we fail to get a public DNS name or public IP, use the private
            // IP.
            if (host == null || host.equals("")) {
                host = inst.getPrivateIpAddress();
            }

            return host;
        }
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
