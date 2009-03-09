package hudson.plugins.ec2.ssh;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
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

import java.io.IOException;
import java.io.PrintStream;

/**
 * {@link ComputerLauncher} that connects to a Unix slave on EC2 by using SSH.
 * 
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends EC2ComputerLauncher {
    protected void launch(EC2Computer computer, PrintStream logger, Instance inst) throws IOException, EC2Exception, InterruptedException {
        logger.println("Connecting to "+inst.getDnsName());
        final Connection conn = new Connection(inst.getDnsName());
        conn.connect(new HostKeyVerifierImpl(computer.getConsoleOutput()));

        KeyPairInfo key = EC2Cloud.get().getUsableKeyPair();
        boolean isAuthenticated = conn.authenticateWithPublicKey("root", key.getKeyMaterial().toCharArray(), "");

        if (!isAuthenticated) {
            logger.println("Authentication failed");
            return;
        }

        SCPClient scp = conn.createSCPClient();
        String initScript = computer.getNode().initScript;

        if(initScript.trim().length()>0) {
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
                conn.close();
                return;
            }
        }


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
    }

    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
