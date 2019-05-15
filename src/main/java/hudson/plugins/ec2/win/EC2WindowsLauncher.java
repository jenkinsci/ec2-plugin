package hudson.plugins.ec2.win;

import com.google.common.net.HostAndPort;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2HostAddressProvider;
import hudson.plugins.ec2.win.winrm.WindowsProcess;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;

public class EC2WindowsLauncher extends EC2ComputerLauncher {
    private static final String AGENT_JAR = "remoting.jar";

    final long sleepBetweenAttempts = TimeUnit.SECONDS.toMillis(10);

    @Override
    protected void launchScript(EC2Computer computer, TaskListener listener) throws IOException,
            AmazonClientException, InterruptedException {
        final PrintStream logger = listener.getLogger();
        final WinConnection connection = connectToWinRM(computer, logger);
        EC2AbstractSlave node = computer.getNode();
        if (node == null || connection == null) {
            logger.println("Unable to fetch node information");
            return;
        }

        try {
            String initScript = node.initScript;
            String tmpDir = (node.tmpDir != null && !node.tmpDir.equals("") ? node.tmpDir
                    : "C:\\Windows\\Temp\\");

            logger.println("Creating tmp directory if it does not exist");
            connection.execute("if not exist " + tmpDir + " mkdir " + tmpDir);

            if (initScript != null && initScript.trim().length() > 0 && !connection.exists(tmpDir + ".jenkins-init")) {
                logger.println("Executing init script");
                OutputStream init = connection.putFile(tmpDir + "init.bat");
                init.write(initScript.getBytes("utf-8"));

                WindowsProcess initProcess = connection.execute("cmd /c " + tmpDir + "init.bat");
                IOUtils.copy(initProcess.getStdout(), logger);

                int exitStatus = initProcess.waitFor();
                if (exitStatus != 0) {
                    logger.println("init script failed: exit code=" + exitStatus);
                    return;
                }

                OutputStream initGuard = connection.putFile(tmpDir + ".jenkins-init");
                initGuard.write("init ran".getBytes(StandardCharsets.UTF_8));
                logger.println("init script ran successfully");
            }

            OutputStream agentJar = connection.putFile(tmpDir + AGENT_JAR);
            agentJar.write(Jenkins.getInstance().getJnlpJars(AGENT_JAR).readFully());

            logger.println("remoting.jar sent remotely. Bootstrapping it");

            final String jvmopts = node.jvmopts;
            final String remoteFS = node.getRemoteFS();
            final WindowsProcess process = connection.execute("java " + (jvmopts != null ? jvmopts : "") + " -jar "
                    + tmpDir + AGENT_JAR + " -workDir " + remoteFS, 86400);
            computer.setChannel(process.getStdout(), process.getStdin(), logger, new Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    process.destroy();
                    connection.close();
                }
            });
        } catch (Throwable ioe) {
            logger.println("Ouch:");
            ioe.printStackTrace(logger);
        } finally {
            connection.close();
        }
    }

    private WinConnection connectToWinRM(EC2Computer computer, PrintStream logger) throws AmazonClientException,
            InterruptedException {
        EC2AbstractSlave node = computer.getNode();
        if (node == null) {
            return null;
        }

        final long minTimeout = 3000;
        long timeout = node.getLaunchTimeoutInMillis(); // timeout is less than 0 when jenkins is booting up.
        if (timeout < minTimeout) {
            timeout = minTimeout;
        }
        final long startTime = System.currentTimeMillis();

        logger.println(node.getDisplayName() + " booted at " + node.getCreatedTime());
        boolean alreadyBooted = (startTime - node.getCreatedTime()) > TimeUnit.MINUTES.toMillis(3);
        while (true) {
            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if (waitTime > timeout) {
                    throw new AmazonClientException("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for winrm to be connected");
                }
                Instance instance = computer.updateInstanceDescription();
                String host = EC2HostAddressProvider.windows(instance, computer.getSlaveTemplate().connectionStrategy).getHostText();

                if ("0.0.0.0".equals(host)) {
                    logger.println("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                logger.println("Connecting to " + "(" + host + ") with WinRM as " + node.remoteAdmin);

                WinConnection connection = new WinConnection(host, node.remoteAdmin, node.getAdminPassword().getPlainText());

                connection.setUseHTTPS(node.isUseHTTPS());
                if (!connection.ping()) {
                    logger.println("Waiting for WinRM to come up. Sleeping 10s.");
                    Thread.sleep(sleepBetweenAttempts);
                    continue;
                }

                if (!alreadyBooted || node.stopOnTerminate) {
                    logger.println("WinRM service responded. Waiting for WinRM service to stabilize on "
                            + node.getDisplayName());
                    Thread.sleep(node.getBootDelay());
                    alreadyBooted = true;
                    logger.println("WinRM should now be ok on " + node.getDisplayName());
                    if (!connection.ping()) {
                        logger.println("WinRM not yet up. Sleeping 10s.");
                        Thread.sleep(sleepBetweenAttempts);
                        continue;
                    }
                }

                logger.println("Connected with WinRM.");
                return connection; // successfully connected
            } catch (IOException e) {
                logger.println("Waiting for WinRM to come up. Sleeping 10s.");
                Thread.sleep(sleepBetweenAttempts);
            }
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
