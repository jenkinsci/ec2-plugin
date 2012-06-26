package hudson.plugins.ec2.ssh;

import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;

import org.apache.commons.io.IOUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.KeyPair;
import com.trilead.ssh2.Connection;
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
    private final int RECONNECT=-2;
    
    protected String buildUpCommand(EC2Computer computer, String command) {
    	if (!computer.getRemoteAdmin().equals("root")) {
    		command = computer.getRootCommandPrefix() + " " + command;
    	}
    	return command;
    }


    @Override
	protected void launch(EC2Computer computer, PrintStream logger, Instance inst) throws IOException, AmazonClientException, InterruptedException {

        final Connection bootstrapConn;
        final Connection conn;
        Connection cleanupConn = null; // java's code path analysis for final doesn't work that well.
        boolean successful = false;
        
        try {
            bootstrapConn = connectToSsh(computer, logger);
            int bootstrapResult = bootstrap(bootstrapConn, computer, logger);
            if (bootstrapResult == FAILED)
                return; // bootstrap closed for us.
            else if (bootstrapResult == SAMEUSER)
                cleanupConn = bootstrapConn; // take over the connection
            else {
                // connect fresh as ROOT
                cleanupConn = connectToSsh(computer, logger);
                KeyPair key = EC2Cloud.get().getKeyPair();
                if (!cleanupConn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getKeyMaterial().toCharArray(), "")) {
                    logger.println("Authentication failed");
                    return; // failed to connect as root.
                }
            }
            conn = cleanupConn;

            SCPClient scp = conn.createSCPClient();
            String initScript = computer.getNode().initScript;

            if(initScript!=null && initScript.trim().length()>0 && conn.exec("test -e ~/.hudson-run-init", logger) !=0) {
                logger.println("Executing init script");
                scp.put(initScript.getBytes("UTF-8"),"init.sh","/tmp","0700");
                Session sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(buildUpCommand(computer, "/tmp/init.sh"));

                sess.getStdin().close();    // nothing to write here
                sess.getStderr().close();   // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(),logger);

                int exitStatus = waitCompletion(sess);
                if (exitStatus!=0) {
                    logger.println("init script failed: exit code="+exitStatus);
                    return;
                }

                // Needs a tty to run sudo.
                sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(buildUpCommand(computer, "touch ~/.hudson-run-init"));
            }

            // TODO: parse the version number. maven-enforcer-plugin might help
            logger.println("Verifying that java exists");
            if(conn.exec("java -fullversion", logger) !=0) {
                logger.println("Installing Java");

                String jdk = "java1.6.0_12";
                String path = "/hudson-ci/jdk/linux-i586/" + jdk + ".tgz";

                URL url = EC2Cloud.get().buildPresignedURL(path);
                if(conn.exec("wget -nv -O /tmp/" + jdk + ".tgz '" + url + "'", logger) !=0) {
                    logger.println("Failed to download Java");
                    return;
                }

                if(conn.exec(buildUpCommand(computer, "tar xz -C /usr -f /tmp/" + jdk + ".tgz"), logger) !=0) {
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
                    "slave.jar","/tmp");

            String jvmopts = computer.getNode().jvmopts;
            String launchString = "java " + (jvmopts != null ? jvmopts : "") + " -jar /tmp/slave.jar";
            logger.println("Launching slave agent: " + launchString);
            final Session sess = conn.openSession();
            sess.execCommand(launchString);
            computer.setChannel(sess.getStdout(),sess.getStdin(),logger,new Listener() {
                @Override
				public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });
            successful = true;
        } finally {
            if(cleanupConn != null && !successful)
                cleanupConn.close();
        }
    }

    private int bootstrap(Connection bootstrapConn, EC2Computer computer, PrintStream logger) throws IOException, InterruptedException, AmazonClientException {
        boolean closeBootstrap = true;
        try {
            int tries = 20;
            boolean isAuthenticated = false;
            KeyPair key = EC2Cloud.get().getKeyPair();
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
        while(true) {
            try {
                Instance instance = computer.updateInstanceDescription();
                String vpc_id = instance.getVpcId();
                String host;

                if (computer.getNode().usePrivateDnsName) {
                    host = instance.getPrivateDnsName();
                } else {
                    /* VPC hosts don't have public DNS names, so we need to use an IP address instead */
                    if (vpc_id == null || vpc_id.equals("")) {
                        host = instance.getPublicDnsName();
                    } else {
                        host = instance.getPrivateIpAddress();
                    }
                }

                if ("0.0.0.0".equals(host)) {
                    logger.println("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                int port = computer.getSshPort();
                logger.println("Connecting to " + host + " on port " + port + ". ");
                Connection conn = new Connection(host, port);
                // currently OpenSolaris offers no way of verifying the host certificate, so just accept it blindly,
                // hoping that no man-in-the-middle attack is going on.
                conn.connect(new ServerHostKeyVerifier() {
                    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
                        return true;
                    }
                });
                logger.println("Connected via SSH.");
                return conn; // successfully connected
            } catch (IOException e) {
                // keep retrying until SSH comes up
                logger.println("Waiting for SSH to come up. Sleeping 5.");
                Thread.sleep(5000);
            }
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
