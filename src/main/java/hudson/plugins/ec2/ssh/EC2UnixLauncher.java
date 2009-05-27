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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;

/**
 * {@link ComputerLauncher} that connects to a Unix slave on EC2 by using SSH.
 * 
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends EC2ComputerLauncher {
    protected void launch(EC2Computer computer, PrintStream logger, Instance inst) throws IOException, EC2Exception, InterruptedException, S3ServiceException {
        logger.println("Connecting to "+inst.getDnsName());
        final Connection conn = new Connection(inst.getDnsName());
        boolean successful = false;

        try {
            // the way the host key is reported is different from AMI to AMI,
            // so there's no reliabele way to do this.
            // conn.connect(new HostKeyVerifierImpl(computer.getConsoleOutput()));
            conn.connect(new ServerHostKeyVerifier() {
                public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
                    return true;
                }
            });

            KeyPairInfo key = EC2Cloud.get().getKeyPair();
            boolean isAuthenticated = conn.authenticateWithPublicKey("root", key.getKeyMaterial().toCharArray(), "");

            if (!isAuthenticated) {
                logger.println("Authentication failed");
                return;
            }

            SCPClient scp = conn.createSCPClient();
            String initScript = computer.getNode().initScript;

            if(initScript!=null && initScript.trim().length()>0) {
                logger.println("Executing init script");
                scp.put(initScript.getBytes("UTF-8"),"init.sh","/tmp","0700");
                Session sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand("/tmp/init.sh");

                sess.getStdin().close();    // nothing to write here
                sess.getStderr().close();   // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(),logger);

                int exitStatus = sess.getExitStatus();
                sess.close();

                if(exitStatus !=0) {
                    logger.println("init script failed: exit code="+exitStatus);
                    return;
                }
            }

            // TODO: parse the version number. maven-enforcer-plugin might help
            logger.println("Verifying that java exists");
            if(exec(conn,"java -fullversion",logger)!=0) {
                logger.println("Installing Java");

                String jdk = "java1.6.0_12";
                String path = "/hudson-ci/jdk/linux-i586/" + jdk + ".tgz";

                URL url = EC2Cloud.get().buildPresignedURL(path);
                if(exec(conn,"wget -nv -O /usr/"+jdk+".tgz '"+url+"'",logger)!=0) {
                    logger.println("Failed to download Java");
                    return;
                }

                if(exec(conn,"tar xz -C /usr -f /usr/"+jdk+".tgz",logger)!=0) {
                    logger.println("Failed to install Java");
                    return;
                }

                if(exec(conn,"ln -s /usr/"+jdk+"/bin/java /bin/java",logger)!=0) {
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
            sess.execCommand("java -jar /tmp/slave.jar");
            computer.setChannel(sess.getStdout(),sess.getStdin(),logger,new Listener() {
                public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });
            successful = true;
        } finally {
            if(!successful)
                conn.close();
        }
    }

    /**
     * Executes a process remotely and blocks until its completion.
     *
     * TODO: update to a new version that has thsi as a convenience method in it.
     *
     * @param output
     *      The stdout/stderr will be sent to this stream.
     */
    public int exec(Connection ssh, String command, OutputStream output) throws IOException, InterruptedException {
        Session session = ssh.openSession();
        try {
            session.execCommand(command);
            PumpThread t1 = new PumpThread(session.getStdout(), output);
            t1.start();
            PumpThread t2 = new PumpThread(session.getStderr(), output);
            t2.start();
            session.getStdin().close();
            t1.join();
            t2.join();
            // I noticed that the exit status delivery often gets delayed. Wait up to 1 sec.
            for( int i=0; i<10; i++ ) {
                Integer r = session.getExitStatus();
                if(r!=null) return r;
                Thread.sleep(100);
            }
            return -1;
        } finally {
            session.close();
        }
    }

    /**
     * Pumps {@link InputStream} to {@link OutputStream}.
     *
     * @author Kohsuke Kawaguchi
     */
    private static final class PumpThread extends Thread {
        private final InputStream in;
        private final OutputStream out;

        public PumpThread(InputStream in, OutputStream out) {
            super("pump thread");
            this.in = in;
            this.out = out;
        }

        public void run() {
            byte[] buf = new byte[1024];
            try {
                while(true) {
                    int len = in.read(buf);
                    if(len<0) {
                        in.close();
                        return;
                    }
                    out.write(buf,0,len);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
