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
import hudson.model.Hudson;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.List;

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

    private final int FAILED=-1;
    private final int SAMEUSER=0;

    protected String buildUpCommand(EC2Computer computer, String command) {
    	if (!computer.getRemoteAdmin().equals("root")) {
    		command = computer.getRootCommandPrefix() + " " + command;
    	}
    	return command;
    }


    @Override
	protected void launch(EC2Computer computer, TaskListener listener, Instance inst) throws IOException, AmazonClientException, InterruptedException {
        final Connection bootstrapConn;
        final Connection conn;
        Connection cleanupConn = null; // java's code path analysis for final doesn't work that well.
        boolean successful = false;
        PrintStream logger = listener.getLogger();

        try {
            bootstrapConn = connectToSsh(computer, logger);
            int bootstrapResult = bootstrap(bootstrapConn, computer, logger);
            if (bootstrapResult == FAILED) {
            	logger.println("bootstrapresult failed");
                return; // bootstrap closed for us.
            }
            else if (bootstrapResult == SAMEUSER) {
                cleanupConn = bootstrapConn; // take over the connection
                logger.println("take over connection");
            }
            else {
                // connect fresh as ROOT
            	logger.println("connect fresh as root");
                cleanupConn = connectToSsh(computer, logger);
                KeyPair key = computer.getCloud().getKeyPair();
                if (!cleanupConn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getKeyMaterial().toCharArray(), "")) {
                    logger.println("Authentication failed");
                    return; // failed to connect as root.
                }
            }
            conn = cleanupConn;

            SCPClient scp = conn.createSCPClient();
            String initScript = computer.getNode().initScript;
            String tmpDir = (Util.fixEmptyAndTrim(computer.getNode().tmpDir) != null ? computer.getNode().tmpDir : "/tmp");

            logger.println("Creating tmp directory (" + tmpDir + ") if it does not exist");
            conn.exec("mkdir -p " + tmpDir, logger);

            if(initScript!=null && initScript.trim().length()>0 && conn.exec("test -e ~/.hudson-run-init", logger) !=0) {
                logger.println("Executing init script");
                scp.put(initScript.getBytes("UTF-8"),"init.sh",tmpDir,"0700");
                Session sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(buildUpCommand(computer, tmpDir + "/init.sh"));

                sess.getStdin().close();    // nothing to write here
                sess.getStderr().close();   // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(),logger);

                int exitStatus = waitCompletion(sess);
                if (exitStatus!=0) {
                    logger.println("init script failed: exit code="+exitStatus);
                    return;
                }
                sess.close();

                // Needs a tty to run sudo.
                sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(buildUpCommand(computer, "touch ~/.hudson-run-init"));
                sess.close();
            }

            // TODO: parse the version number. maven-enforcer-plugin might help
            logger.println("Verifying that java exists");
            if(conn.exec("java -fullversion", logger) !=0) {
                logger.println("Installing Java");

                String jdk = "java1.6.0_12";
                String path = "/hudson-ci/jdk/linux-i586/" + jdk + ".tgz";

                URL url = computer.getCloud().buildPresignedURL(path);
                if(conn.exec("wget -nv -O " + tmpDir + "/" + jdk + ".tgz '" + url + "'", logger) !=0) {
                    logger.println("Failed to download Java");
                    return;
                }

                if(conn.exec(buildUpCommand(computer, "tar xz -C /usr -f " + tmpDir + "/" + jdk + ".tgz"), logger) !=0) {
                    logger.println("Failed to install Java");
                    return;
                }

                if(conn.exec(buildUpCommand(computer, "ln -s /usr/" + jdk + "/bin/java /bin/java"), logger) !=0) {
                    logger.println("Failed to symlink Java");
                    return;
                }
            }

            // TODO: on Windows with ec2-sshd, this scp command ends up just putting slave.jar as c:\tmp
            // bug in ec2-sshd?

            logger.println("Copying slave.jar");
            scp.put(Hudson.getInstance().getJnlpJars("slave.jar").readFully(),
                    "slave.jar",tmpDir);

            String jvmopts = computer.getNode().jvmopts;
            String launchString = "java " + (jvmopts != null ? jvmopts : "") + " -jar " + tmpDir + "/slave.jar";

            SlaveTemplate slaveTemplate = getSlaveTemplate(computer);

            if (slaveTemplate != null && slaveTemplate.isConnectBySSHProcess()) {
                EC2AbstractSlave node = computer.getNode();
                File identityKeyFile = createIdentityKeyFile(computer);

                try {
                    // Obviously the master must have an installed ssh client.
                    String sshClientLaunchString =
                            String.format("ssh -o StrictHostKeyChecking=no -i %s %s@%s -p %d %s",
                                    identityKeyFile.getAbsolutePath(),
                                    node.remoteAdmin,
                                    getEC2HostAddress(computer, inst),
                                    node.getSshPort(),
                                    launchString);

                    logger.println("Launching slave agent (via SSH client process): " + sshClientLaunchString);
                    CommandLauncher commandLauncher = new CommandLauncher(sshClientLaunchString);
                    commandLauncher.launch(computer, listener);
                } finally {
                    identityKeyFile.delete();
                }
            } else {
                logger.println("Launching slave agent (via Trilead SSH2 Connection): " + launchString);
                final Session sess = conn.openSession();
                sess.execCommand(launchString);
                computer.setChannel(sess.getStdout(),sess.getStdin(),logger,new Listener() {
                    @Override
                    public void onClosed(Channel channel, IOException cause) {
                        sess.close();
                        conn.close();
                    }
                });
            }

            successful = true;
        } finally {
            if(cleanupConn != null && !successful)
                cleanupConn.close();
        }
    }
    
    /**
     * Return the first {@link SlaveTemplate} from the given {@link EC2Computer} instance.
     * @param computer
     * @return
     */
    private SlaveTemplate getSlaveTemplate(EC2Computer computer)
    {
        // TODO: I don't get these templates. How are they named/labeled and when will there be multiples?
        // Need to find out how to map this stuff onto the EC2AbstractSlave instance as it seems to have
        // some of the same props.
        List<SlaveTemplate> templates = computer.getCloud().getTemplates();
        SlaveTemplate slaveTemplate = null;
        if (templates != null && !templates.isEmpty()) {
            slaveTemplate = templates.get(0);
        }
        return slaveTemplate;
    }

    private File createIdentityKeyFile(EC2Computer computer) throws IOException {
        String privateKey = computer.getCloud().getPrivateKey().getPrivateKey();
        File tempFile = File.createTempFile("ec2_", ".pem");

        try {
            FileWriter writer = new FileWriter(tempFile);
            try {
                writer.write(privateKey);
                writer.flush();
            } finally {
                writer.close();
            }
            FilePath filePath = new FilePath(tempFile);
            filePath.chmod(0400); // octal file mask - readonly by owner
            return tempFile;
        } catch (Exception e) {
            tempFile.delete();
            throw new IOException("Error creating temporary identity key file for connecting to EC2 slave.", e);
        }
    }

    private int bootstrap(Connection bootstrapConn, EC2Computer computer, PrintStream logger) throws IOException, InterruptedException, AmazonClientException {
        logger.println("bootstrap()" );
        boolean closeBootstrap = true;
        try {
            int tries = 20;
            boolean isAuthenticated = false;
            logger.println("Getting keypair..." );
            KeyPair key = computer.getCloud().getKeyPair();
            logger.println("Using key: " + key.getKeyName() + "\n" + key.getKeyFingerprint() + "\n" + key.getKeyMaterial().substring(0, 160) );
            while (tries-- > 0) {
                logger.println("Authenticating as " + computer.getRemoteAdmin());
                isAuthenticated = bootstrapConn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getKeyMaterial().toCharArray(), "");
                if (isAuthenticated) {
                    break;
                }
                logger.println("Authentication failed. Trying again...");
                Thread.sleep(10000);
            }
            if (!isAuthenticated) {
                logger.println("Authentication failed");
                return FAILED;
            }
            closeBootstrap = false;
            return SAMEUSER;
        } finally {
            if (closeBootstrap)
                bootstrapConn.close();
        }
    }

    private Connection connectToSsh(EC2Computer computer, PrintStream logger) throws AmazonClientException, InterruptedException {
        final long timeout = computer.getNode().getLaunchTimeoutInMillis();
        final long startTime = System.currentTimeMillis();
        while(true) {
            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if ( timeout > 0 && waitTime > timeout )
                {
                    throw new AmazonClientException("Timed out after "+ (waitTime / 1000) + " seconds of waiting for ssh to become available. (maximum timeout configured is "+ (timeout / 1000) + ")" );
                }
                Instance instance = computer.updateInstanceDescription();
                String host = getEC2HostAddress(computer, instance);

                if ("0.0.0.0".equals(host)) {
                    logger.println("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                int port = computer.getSshPort();
                Integer slaveConnectTimeout = Integer.getInteger("jenkins.ec2.slaveConnectTimeout", 10000);
                logger.println("Connecting to " + host + " on port " + port + ", with timeout " + slaveConnectTimeout + ".");
                Connection conn = new Connection(host, port);
                ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
                Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(host);
                if (! proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
                    InetSocketAddress address = (InetSocketAddress) proxy.address();
                    HTTPProxyData proxyData = null;
                    if (null != proxyConfig.getUserName()) {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort(), proxyConfig.getUserName(), proxyConfig.getPassword());
                    } else {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort());
                    }
                    conn.setProxyData(proxyData);
                    logger.println("Using HTTP Proxy Configuration");
                }
                // currently OpenSolaris offers no way of verifying the host certificate, so just accept it blindly,
                // hoping that no man-in-the-middle attack is going on.
                conn.connect(new ServerHostKeyVerifier() {
                    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
                        return true;
                    }
                }, slaveConnectTimeout, slaveConnectTimeout);
                logger.println("Connected via SSH.");
                return conn; // successfully connected
            } catch (IOException e) {
                // keep retrying until SSH comes up
                logger.println("Failed to connect via ssh: " + e.getMessage());
                logger.println("Waiting for SSH to come up. Sleeping 5.");
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
            // (but only if the plugin config let us use the public IP to connect to the slave).
            if (host == null || host.equals("")) {
                SlaveTemplate slaveTemplate = getSlaveTemplate(computer);
                if (inst.getPublicIpAddress() != null && slaveTemplate.isConnectUsingPublicIp())
                {
                    host = inst.getPublicIpAddress();
                }
            }
            // If we fail to get a public DNS name or public IP, use the private IP.
            if (host == null || host.equals("")) {
                host = inst.getPrivateIpAddress();
            }
            
            return host;
        }
    }

    private int waitCompletion(Session session) throws InterruptedException {
        // I noticed that the exit status delivery often gets delayed. Wait up to 1 sec.
        for( int i=0; i<10; i++ ) {
            Integer r = session.getExitStatus();
            if(r!=null) return r;
            Thread.sleep(100);
        }
        return -1;
    }

    @Override
	public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
