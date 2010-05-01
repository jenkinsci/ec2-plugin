package hudson.plugins.ec2.ssh;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;
import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.KeyPairInfo;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import org.apache.commons.io.IOUtils;
import org.jets3t.service.S3ServiceException;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;

/**
 * {@link ComputerLauncher} that connects to a Unix slave on EC2 by using SSH.
 * 
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends EC2ComputerLauncher {

    private final int FAILED=-1;
    private final int SAMEUSER=0;
    private final int RECONNECT=-2;

    protected void launch(EC2Computer computer, PrintStream logger, Instance inst) throws IOException, EC2Exception, InterruptedException, S3ServiceException {
        logger.println("Connecting to "+inst.getDnsName());
        final Connection bootstrapConn;
        final Connection conn;
        Connection cleanupConn = null; // java's code path analysis for final doesn't work that well.
        boolean successful = false;
        
        try {
            bootstrapConn = connectToSsh(inst);
            int bootstrapResult = bootstrap(bootstrapConn, computer, logger);
            if (bootstrapResult == FAILED)
                return; // bootstrap closed for us.
            else if (bootstrapResult == SAMEUSER)
                cleanupConn = bootstrapConn; // take over the connection
            else {
                // connect fresh as ROOT
                cleanupConn = connectToSsh(inst);
                KeyPairInfo key = EC2Cloud.get().getKeyPair();
                if (!cleanupConn.authenticateWithPublicKey("root", key.getKeyMaterial().toCharArray(), "")) {
                    logger.println("Authentication failed");
                    return; // failed to connect as root.
                }
            }
            conn = cleanupConn;

            SCPClient scp = conn.createSCPClient();
            String initScript = computer.getNode().initScript;

            if(initScript!=null && initScript.trim().length()>0 && conn.exec("test -e /.hudson-run-init", logger) !=0) {
                logger.println("Executing init script");
                scp.put(initScript.getBytes("UTF-8"),"init.sh","/tmp","0700");
                Session sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(computer.getRootCommandPrefix() + "/tmp/init.sh");

                sess.getStdin().close();    // nothing to write here
                sess.getStderr().close();   // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(),logger);

                int exitStatus = waitCompletion(sess);
                if (exitStatus!=0) {
                    logger.println("init script failed: exit code="+exitStatus);
                    return;
                }

                // leave the completion marker
                scp.put(new byte[0],".hudson-run-init","/","0600");

            }

            // TODO: parse the version number. maven-enforcer-plugin might help
            logger.println("Verifying that java exists");
            if(conn.exec("java -fullversion", logger) !=0) {
                logger.println("Installing Java");

                String jdk = "java1.6.0_12";
                String path = "/hudson-ci/jdk/linux-i586/" + jdk + ".tgz";

                URL url = EC2Cloud.get().buildPresignedURL(path);
                if(conn.exec("wget -nv -O /usr/" + jdk + ".tgz '" + url + "'", logger) !=0) {
                    logger.println("Failed to download Java");
                    return;
                }

                if(conn.exec("tar xz -C /usr -f /usr/" + jdk + ".tgz", logger) !=0) {
                    logger.println("Failed to install Java");
                    return;
                }

                if(conn.exec("ln -s /usr/" + jdk + "/bin/java /bin/java", logger) !=0) {
                    logger.println("Failed to symlink Java");
                    return;
                }
            }

            // TODO: on Windows with ec2-sshd, this scp command ends up just putting slave.jar as c:\tmp
            // bug in ec2-sshd?

            logger.println("Copying slave.jar");
            scp.put(Hudson.getInstance().getJnlpJars("slave.jar").readFully(),
                    "slave.jar","/tmp");

            logger.println("Launching slave agent");
            final Session sess = conn.openSession();
            sess.execCommand("java " + computer.getNode().jvmopts + " -jar /tmp/slave.jar");
            computer.setChannel(sess.getStdout(),sess.getStdin(),logger,new Listener() {
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

    private int bootstrap(Connection bootstrapConn, EC2Computer computer, PrintStream logger) throws IOException, InterruptedException, EC2Exception {
        boolean closeBootstrap = true;
        try {
            int tries = 20;
            boolean isAuthenticated = false;
            KeyPairInfo key = EC2Cloud.get().getKeyPair();
            while (tries-- > 0) {
                isAuthenticated = bootstrapConn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getKeyMaterial().toCharArray(), "");
                if (isAuthenticated) {
                    break;
                }
                logger.println("Authentication failed. Trying again...");
                Thread.currentThread().sleep(10000);
            }
            if (!isAuthenticated) {
                logger.println("Authentication failed");
                return FAILED;
            }
            if (!computer.getRemoteAdmin().equals("root")) {
                // Get root working, so we can scp in etc.
                Session sess = bootstrapConn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(computer.getRootCommandPrefix() + "cp ~/.ssh/authorized_keys /root/.ssh/");
                sess.getStdin().close(); // nothing to write here
                sess.getStderr().close(); // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(), logger);
                int exitStatus = waitCompletion(sess);
                if (exitStatus != 0) {
                    logger.println("init script failed: exit code=" + exitStatus);
                    return FAILED;
                }
                return RECONNECT;
            } else {
                closeBootstrap = false;
                return SAMEUSER;
            }
        } finally {
            if (closeBootstrap)
                bootstrapConn.close();
        }
    }

    private Connection connectToSsh(Instance inst) throws InterruptedException {
        while(true) {
            try {
                Connection conn = new Connection(inst.getDnsName(),22);
                // currently OpenSolaris offers no way of verifying the host certificate, so just accept it blindly,
                // hoping that no man-in-the-middle attack is going on.
                conn.connect(new ServerHostKeyVerifier() {
                    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
                        return true;
                    }
                });
                return conn; // successfully connected
            } catch (IOException e) {
                // keep retrying until SSH comes up
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

    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
