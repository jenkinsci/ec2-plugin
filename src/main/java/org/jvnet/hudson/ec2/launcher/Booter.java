package org.jvnet.hudson.ec2.launcher;

import static org.jvnet.hudson.ec2.launcher.InstanceState.PENDING;
import static org.jvnet.hudson.ec2.launcher.InstanceState.RUNNING;
import org.jvnet.hudson.ec2.launcher.gui.Page;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.ReservationDescription;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;
import org.apache.commons.io.IOUtils;

import javax.swing.*;
import static javax.swing.JOptionPane.ERROR_MESSAGE;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 * Executes the boot up sequence of Hudson.
 *
 * @author Kohsuke Kawaguchi
 */
public class Booter extends Thread {
    private final Page owner;
    private final Launcher launcher;
    /**
     * This is where the output of the boot is sent.
     */
    private final PrintStream console;
    private final OutputStream src;

    public Booter(Page owner, OutputStream console) {
        super("boot thread");
        this.owner = owner;
        this.launcher = owner.launcher;
        this.src = console;
        this.console = new PrintStream(new NoCloseOutputStream(console),true);
    }

    @Override
    public void run() {
        Connection ssh=null;
        try {
            reportStatus("Starting EC2 instance");
            Instance inst=null;
            if(Boolean.getBoolean("hudson.attach"))
                // find if there's an instance running, and if so, attach. useful during the debugging.
                inst = findInstance();
            if(inst==null)
                inst = launcher.start();

            // 2009/03/18 I noticed that the Instance object EC2 gives us on a new start no longer contains full fields.

            reportStatus("Waiting for the instance to boot");
            InstanceState state;
            do {
                Thread.sleep(5000);
                state = launcher.checkBootStatus(inst);
            } while (state==PENDING);

            if(state!=RUNNING) {
                reportError("EC2 instance failed to boot: "+state);
                return;
            }

            // obtain the full information from ID
            String id = inst.getInstanceId();
            inst = findInstanceById(id);
            if(inst==null) {
                reportError("Failed to re-discover instanceId "+id+". But how is that possible!?");
                return;
            }

            reportStatus("Connecting to "+inst.getDnsName());
            while(true) {
                try {
                    ssh = new Connection(inst.getDnsName(),22);
                    // currently OpenSolaris offers no way of verifying the host certificate, so just accept it blindly,
                    // hoping that no man-in-the-middle attack is going on.
                    ssh.connect(new ServerHostKeyVerifier() {
                        public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
                            return true;
                        }
                    });
                    break; // successfully connected
                } catch (IOException e) {
                    // keep retrying until SSH comes up
                    Thread.sleep(5000);
                    continue;
                }
            }

            reportStatus("Authenticating");
            if(!ssh.authenticateWithPublicKey("root",launcher.getPrivateKey().file,null))
                throw new OperatorErrorException("SSH authentication failed. But how could this happen!?");

            reportStatus("Attaching storage");
            Storage s = launcher.getStorage();
            List<String> devices = s.attach(inst, launcher.getEc2());

            reportStatus("Determining the file system status");
            if(ssh.exec("zpool import hudson", console) ==0) {
                // imported successful
                reportStatus("Successfully reattached to the file system");
            } else {
                // no file system to import. create.
                // TODO: user confirmation?
                reportStatus("Creating a file system");
                if(ssh.exec("zpool create hudson " + join(devices), console) !=0) {
                    reportError("Failed to create a file system.");
                    return;
                }
            }

            reportStatus("Copying keys");
            SCPClient scp = new SCPClient(ssh);
            scp.put(launcher.getPrivateKey().file.getPath(),"ec2.key","/root","0600");
            scp.put(launcher.getAccessId().getBytes(),"accessId.txt","/root","0600");
            scp.put(launcher.getSecretKey().getBytes(),"secretKey.txt","/root","0600");

            if(ssh.exec("test -f /hudson/hudson.war", console) !=0) {
                reportStatus("Installing hudson.war");
                // TODO: install via IPS or launch through SMF?
                if(ssh.exec("wget --no-check-certificate --no-verbose -O /hudson/hudson.wget http://hudson-ci.org/latest/hudson.war", console) !=0) {
                    reportError("Failed to download hudson.war");
                    return;
                }

                if(ssh.exec("mv /hudson/hudson.wget /hudson/hudson.war", console) !=0) {
                    reportError("Failed to stage hudson.war");
                    return;
                }

                // create the init script, to run Hudson in a secured state
                scp.put(IOUtils.toByteArray(Booter.class.getResourceAsStream("init.groovy")),"init.groovy","/hudson","0600");
            }

            // install EC2 plugin
            if(ssh.exec("test -f /hudson/plugins/ec2.hpi", console) !=0) {
                reportStatus("Installing EC2 plugin");
                ssh.exec("mkdir /hudson/plugins", console);
                if(ssh.exec("wget --no-check-certificate --no-verbose -O /hudson/plugins/ec2.hpi http://hudson-ci.org/latest/ec2.hpi", console) !=0) {
                    reportError("Failed to download ec2.hpi");
                    return;
                }
            }

            if(ssh.exec("test -f /hudson/hudson.xml", console) !=0) {
                reportStatus("Copying SMF manifest");
                scp.put(IOUtils.toByteArray(Booter.class.getResourceAsStream("smf.xml")),"hudson.xml","/hudson");
            }

            reportStatus("Registering the service");
            if(ssh.exec("svccfg import /hudson/hudson.xml", console) !=0) {
                reportError("Failed to register the Hudson service");
                return;
            }

            Thread.sleep(500); // not sure if needed, but give SMF some time to start the service

            reportStatus("Waiting for the service to become active");
            for(int i=0; ; i++) {
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                if(ssh.exec("svcs -H -o sta hudson", stdout) !=0) {
                    reportError("Failed to monitor the status of the Hudson service");
                    return;
                }
                String status = stdout.toString().trim();
                if(status.equals("ON"))
                    break;
                if(status.equals("MNT")) {
                    reportError("Hudson service failed to start");
                    return;
                }
                Thread.sleep(1000);
                if(i>5) {
                    // after 5 secs, start showing the output
                    console.println(status);
                }
                if(i>10) {
                    // taking too long abort
                    reportError("Failed to start Hudson service. Is it hanging?");
                    return;
                }
            }

            // again give a bit of time for Hudson to start listening on 80
            Thread.sleep(2000);

            reportStatus("Hudson started successfully");

            try {
                // hit the login URL to automatically logs this user.
                Desktop.getDesktop().browse(new URL("http://"+inst.getDnsName()+"/j_acegi_security_check"
                        +"?remember_me=true&j_username="+launcher.getAccessId()+"&j_password="+launcher.getSecretKey()).toURI());
            } catch (LinkageError e) {
                // ignore if we failed to start a browser
            }

            EventQueue.invokeLater(new Runnable() {
                public void run() {
                    owner.setComplete(true);
                }
            });
        } catch (Exception e) {
            handleException(e);
        } finally {
            if(ssh!=null)
                ssh.close();
            EventQueue.invokeLater(new Runnable() {
                public void run() {
                    owner.setBusy(false);
                }
            });
            try {
                src.close();
            } catch (IOException e) {
                // ignore
            }
            onEnd();
        }
    }

    protected void onEnd() {
    }

    private Instance findInstance() throws EC2Exception {
        for (ReservationDescription r : launcher.getEc2().describeInstances(Collections.<String>emptyList())) {
            for (Instance i : r.getInstances()) {
                if(i.isPending() || i.isRunning())
                    return i;
            }
        }
        return null;
    }

    private Instance findInstanceById(String instanceId) throws EC2Exception {
        for (ReservationDescription r : launcher.getEc2().describeInstances(Collections.<String>emptyList())) {
            for (Instance i : r.getInstances()) {
                if(i.getInstanceId().equals(instanceId))
                    return i;
            }
        }
        return null;
    }

    protected void reportStatus(String msg) {
        console.println(msg);
    }

    protected void reportError(final String msg) {
        console.println(msg);
        EventQueue.invokeLater(new Runnable() {
            public void run() {
                JOptionPane.showMessageDialog(owner,msg,"Error", ERROR_MESSAGE);
            }
        });
    }

    private void handleException(final Exception e) {
        e.printStackTrace(console);
        EventQueue.invokeLater(new Runnable() {
            public void run() {
                JOptionPane.showMessageDialog(owner,e.getMessage(),"Error", ERROR_MESSAGE);
            }
        });
    }

    private String join(List<String> devices) {
        StringBuilder buf = new StringBuilder();
        for (String dev : devices)
            buf.append(' ').append(dev);
        return buf.toString();
    }
}
