package hudson.plugins.ec2.win;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.GetPasswordDataRequest;
import com.amazonaws.services.ec2.model.GetPasswordDataResult;
import com.amazonaws.services.ec2.model.Instance;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.os.WindowsUtil;
import hudson.plugins.ec2.*;
import hudson.plugins.ec2.win.winrm.WindowsProcess;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

public class EC2WindowsLauncher extends EC2ComputerLauncher {
    private static final String AGENT_JAR = "remoting.jar";

    final long sleepBetweenAttempts = TimeUnit.SECONDS.toMillis(10);

    @Override
    protected void launchScript(EC2Computer computer, TaskListener listener)
            throws IOException, AmazonClientException, InterruptedException {
        final PrintStream logger = listener.getLogger();
        EC2AbstractSlave node = computer.getNode();
        if (node == null) {
            logger.println("Unable to fetch node information");
            return;
        }
        final SlaveTemplate template = computer.getSlaveTemplate();
        if (template == null) {
            throw new IOException("Could not find corresponding agent template for " + computer.getDisplayName());
        }

        final WinConnection connection = connectToWinRM(computer, node, template, logger);

        try {
            String initScript = node.initScript;
            String tmpDir = (node.tmpDir != null && !node.tmpDir.equals("")
                    ? WindowsUtil.quoteArgument(Util.ensureEndsWith(node.tmpDir, "\\"))
                    : "C:\\Windows\\Temp\\");

            logger.println("Creating tmp directory if it does not exist");
            WindowsProcess mkdirProcess = connection.execute("if not exist " + tmpDir + " mkdir " + tmpDir);
            int exitCode = mkdirProcess.waitFor();
            if (exitCode != 0) {
                logger.println("Creating tmpdir failed=" + exitCode);
                return;
            }

            if (initScript != null && !initScript.trim().isEmpty() && !connection.exists(tmpDir + ".jenkins-init")) {
                logger.println("Executing init script");
                try (OutputStream init = connection.putFile(tmpDir + "init.bat")) {
                    init.write(initScript.getBytes("utf-8"));
                }

                WindowsProcess initProcess = connection.execute("cmd /c " + tmpDir + "init.bat");
                IOUtils.copy(initProcess.getStdout(), logger);

                int exitStatus = initProcess.waitFor();
                if (exitStatus != 0) {
                    logger.println("init script failed: exit code=" + exitStatus);
                    return;
                }

                try (OutputStream initGuard = connection.putFile(tmpDir + ".jenkins-init")) {
                    initGuard.write("init ran".getBytes(StandardCharsets.UTF_8));
                }
                logger.println("init script ran successfully");
            }

            try (OutputStream agentJar = connection.putFile(tmpDir + AGENT_JAR)) {
                agentJar.write(Jenkins.get().getJnlpJars(AGENT_JAR).readFully());
            }

            logger.println("remoting.jar sent remotely. Bootstrapping it");

            final String javaPath = node.javaPath;
            final String jvmopts = node.jvmopts;
            final String remoteFS = WindowsUtil.quoteArgument(node.getRemoteFS());
            final String workDir = Util.fixEmptyAndTrim(remoteFS) != null ? remoteFS : tmpDir;
            final String launchString = javaPath + " " + (jvmopts != null ? jvmopts : "") + " -jar " + tmpDir
                    + AGENT_JAR + " -workDir " + workDir;
            logger.println("Launching via WinRM:" + launchString);
            final WindowsProcess process = connection.execute(launchString, 86400);
            computer.setChannel(process.getStdout(), process.getStdin(), logger, new Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    process.destroy();
                    connection.close();
                }
            });
        } catch (EOFException eof) {
            // When we launch java with connection.execute(launchString... it keeps running, but if java is not
            // installed
            // the computer.setChannel fails with EOFException because the stream is already closed. It fails on
            // setChannel - build - negotiate - is.read() == -1. Let's print a clear message to help diagnose the
            // problem
            // In other case you see a EOFException which gives you few clues about the problem.
            logger.println(
                    "The stream with the java process on the instance was closed. Maybe java is not installed there.");
            eof.printStackTrace(logger);
        } catch (Throwable ioe) {
            logger.println("Ouch:");
            ioe.printStackTrace(logger);
        } finally {
            connection.close();
        }
    }

    @NonNull
    private WinConnection connectToWinRM(
            EC2Computer computer, EC2AbstractSlave node, SlaveTemplate template, PrintStream logger)
            throws AmazonClientException, InterruptedException {
        final long minTimeout = 3000;
        long timeout = node.getLaunchTimeoutInMillis(); // timeout is less than 0 when jenkins is booting up.
        if (timeout < minTimeout) {
            timeout = minTimeout;
        }
        final long startTime = System.currentTimeMillis();

        logger.println(node.getDisplayName() + " booted at " + node.getCreatedTime());
        boolean alreadyBooted = (startTime - node.getCreatedTime()) > TimeUnit.MINUTES.toMillis(3);
        WinConnection connection = null;
        while (true) {
            boolean allowSelfSignedCertificate = node.isAllowSelfSignedCertificate();

            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if (waitTime > timeout) {
                    throw new AmazonClientException(
                            "Timed out after " + (waitTime / 1000) + " seconds of waiting for winrm to be connected");
                }

                if (connection == null) {
                    Instance instance = computer.updateInstanceDescription();
                    String host = EC2HostAddressProvider.windows(instance, template.connectionStrategy);

                    // Check when host is null or we will keep trying and receiving a hostname cannot be null forever.
                    if (host == null || "0.0.0.0".equals(host)) {
                        logger.println(
                                "Invalid host (null or 0.0.0.0). Your host is most likely waiting for an IP address.");
                        throw new IOException("goto sleep");
                    }

                    if (!node.isSpecifyPassword()) {
                        GetPasswordDataResult result;
                        try {
                            result = node.getCloud()
                                    .connect()
                                    .getPasswordData(new GetPasswordDataRequest(instance.getInstanceId()));
                        } catch (Exception e) {
                            logger.println("Unexpected Exception: " + e.toString());
                            Thread.sleep(sleepBetweenAttempts);
                            continue;
                        }
                        String passwordData = result.getPasswordData();
                        if (passwordData == null || passwordData.isEmpty()) {
                            logger.println("Waiting for password to be available. Sleeping 10s.");
                            Thread.sleep(sleepBetweenAttempts);
                            continue;
                        }
                        EC2PrivateKey ec2PrivateKey = node.getCloud().resolvePrivateKey();
                        if (ec2PrivateKey == null) {
                            logger.println(
                                    "Waiting for privateKey to be available. Consider checking the credentials in the cloud configuration. Sleeping 10s.");
                            Thread.sleep(sleepBetweenAttempts);
                            continue;
                        }
                        String password = ec2PrivateKey.decryptWindowsPassword(passwordData);
                        if (!node.getRemoteAdmin().equals("Administrator")) {
                            logger.println(
                                    "WARNING: For password retrieval remote admin must be Administrator, ignoring user provided value");
                        }
                        logger.println("Connecting to " + "(" + host + ") with WinRM as Administrator");
                        connection = new WinConnection(host, "Administrator", password, allowSelfSignedCertificate);
                    } else { // password Specified
                        logger.println("Connecting to " + "(" + host + ") with WinRM as " + node.getRemoteAdmin());
                        connection = new WinConnection(
                                host,
                                node.getRemoteAdmin(),
                                node.getAdminPassword().getPlainText(),
                                allowSelfSignedCertificate);
                    }
                    connection.setUseHTTPS(node.isUseHTTPS());
                }

                if (!connection.pingFailingIfSSHHandShakeError()) {
                    logger.println("Waiting for WinRM to come up. Sleeping 10s.");
                    Thread.sleep(sleepBetweenAttempts);
                    continue;
                }

                if (!alreadyBooted || node.stopOnTerminate) {
                    int bootDelay = node.getBootDelay();
                    if (bootDelay > 0) {
                        logger.println("WinRM service responded. Waiting " + bootDelay
                                + "ms for WinRM service to stabilize on " + node.getDisplayName());
                        Thread.sleep(bootDelay);
                        logger.println("WinRM should now be ok on " + node.getDisplayName());
                    }
                    alreadyBooted = true;
                    if (!connection.pingFailingIfSSHHandShakeError()) {
                        logger.println("WinRM not yet up. Sleeping 10s.");
                        Thread.sleep(sleepBetweenAttempts);
                        continue;
                    }
                }

                logger.println("Connected with WinRM.");
                return connection; // successfully connected
            } catch (IOException e) {
                if (e instanceof SSLException) {
                    // To avoid reconnecting continuously
                    computer.setTemporarilyOffline(true, OfflineCause.create(Messages._OfflineCause_SSLException()));
                    // avoid waiting and trying again, this connection needs human intervention to change the
                    // certificate
                    throw new AmazonClientException("The SSL connection failed while negotiating SSL", e);
                }
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
