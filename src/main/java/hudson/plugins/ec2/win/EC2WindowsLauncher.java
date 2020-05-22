package hudson.plugins.ec2.win;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2HostAddressProvider;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.win.winrm.WindowsProcess;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import hudson.Util;
import hudson.os.WindowsUtil;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import hudson.slaves.OfflineCause;
import javax.annotation.Nonnull;

import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.GetPasswordDataRequest;
import com.amazonaws.services.ec2.model.GetPasswordDataResult;

import javax.net.ssl.SSLException;

public class EC2WindowsLauncher extends EC2ComputerLauncher {
    private static final String AGENT_JAR = "remoting.jar";

    private static final String DEFAULT_MAX_SLEEP = Long.toString(TimeUnit.MINUTES.toMillis(30));

    private static long maxGetPassThreadSleep = Long.parseLong(System.getProperty(EC2WindowsLauncher.class.getName() + ".maxGetPassThreadSleep", DEFAULT_MAX_SLEEP));

    private static long maxWinRMThreadSleep = Long.parseLong(System.getProperty(EC2WindowsLauncher.class.getName() + ".maxWinRMThreadSleep", DEFAULT_MAX_SLEEP));

    private long sleepBetweenGetPassRange = TimeUnit.SECONDS.toMillis(1);

    private long sleepBetweenGetPassAttempts = TimeUnit.SECONDS.toMillis(1);

    private long sleepBetweenWinRMRange = TimeUnit.SECONDS.toMillis(1);

    private long sleepBetweenWinRMAttempts = TimeUnit.SECONDS.toMillis(1);

    @Override
    protected void launchScript(EC2Computer computer, TaskListener listener) throws IOException,
            AmazonClientException, InterruptedException {
        final PrintStream logger = listener.getLogger();
        EC2AbstractSlave node = computer.getNode();
        if (node == null) {
            logger.println("Unable to fetch node information");
            return;
        }
        final SlaveTemplate template = computer.getSlaveTemplate();
        if (template == null) {
            throw new IOException("Could not find corresponding slave template for " + computer.getDisplayName());
        }

        final WinConnection connection = connectToWinRM(computer, node, template, logger);

        try {
            String initScript = node.initScript;
            String tmpDir = (node.tmpDir != null && !node.tmpDir.equals("") ? WindowsUtil.quoteArgument(Util.ensureEndsWith(node.tmpDir,"\\"))
                    : "C:\\Windows\\Temp\\");

            logger.println("Creating tmp directory if it does not exist");
            WindowsProcess mkdirProcess = connection.execute("if not exist " + tmpDir + " mkdir " + tmpDir);
            int exitCode = mkdirProcess.waitFor();
            if (exitCode != 0) {
                logger.println("Creating tmpdir failed=" + exitCode);
                return;
            }

            if (initScript != null && initScript.trim().length() > 0 && !connection.exists(tmpDir + ".jenkins-init")) {
                logger.println("Executing init script");
                try(OutputStream init = connection.putFile(tmpDir + "init.bat")) {
                    init.write(initScript.getBytes("utf-8"));
                }

                WindowsProcess initProcess = connection.execute("cmd /c " + tmpDir + "init.bat");
                IOUtils.copy(initProcess.getStdout(), logger);

                int exitStatus = initProcess.waitFor();
                if (exitStatus != 0) {
                    logger.println("init script failed: exit code=" + exitStatus);
                    return;
                }

                try(OutputStream initGuard = connection.putFile(tmpDir + ".jenkins-init")) {
                    initGuard.write("init ran".getBytes(StandardCharsets.UTF_8));
                }
                logger.println("init script ran successfully");
            }

            try(OutputStream agentJar = connection.putFile(tmpDir + AGENT_JAR)) {
                agentJar.write(Jenkins.get().getJnlpJars(AGENT_JAR).readFully());
            }

            logger.println("remoting.jar sent remotely. Bootstrapping it");

            final String jvmopts = node.jvmopts;
            final String remoteFS = WindowsUtil.quoteArgument(node.getRemoteFS());
            final String workDir = Util.fixEmptyAndTrim(remoteFS) != null ? remoteFS : tmpDir;
            final String launchString = "java " + (jvmopts != null ? jvmopts : "") + " -jar " + tmpDir + AGENT_JAR + " -workDir " + workDir;
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
            // When we launch java with connection.execute(launchString... it keeps running, but if java is not installed
            //the computer.setChannel fails with EOFException because the stream is already closed. It fails on
            // setChannel - build - negotiate - is.read() == -1. Let's print a clear message to help diagnose the problem
            // In other case you see a EOFException which gives you few clues about the problem.
            logger.println("The stream with the java process on the instance was closed. Maybe java is not installed there.");
            eof.printStackTrace(logger);
        } catch (Throwable ioe) {
            logger.println("Ouch:");
            ioe.printStackTrace(logger);
        } finally {
            connection.close();
        }
    }

    @Nonnull
    private WinConnection connectToWinRM(EC2Computer computer, EC2AbstractSlave node, SlaveTemplate template, PrintStream logger) throws AmazonClientException,
            InterruptedException {
        final long minTimeout = 3000;
        long timeout = node.getLaunchTimeoutInMillis(); // timeout is less than 0 when jenkins is booting up.
        if (timeout < minTimeout) {
            timeout = minTimeout;
        }
        logger.println(String.format("Launch Timeout set to %ds", TimeUnit.MILLISECONDS.toSeconds(timeout)));
        final long startTime = System.currentTimeMillis();

        logger.println(node.getDisplayName() + " booted at " + node.getCreatedTime());
        boolean alreadyBooted = (startTime - node.getCreatedTime()) > TimeUnit.MINUTES.toMillis(3);
        WinConnection connection = null;
        while (true) {
            boolean allowSelfSignedCertificate = node.isAllowSelfSignedCertificate();

            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if (waitTime > timeout) {
                    throw new AmazonClientException("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for winrm to be connected");
                }

                if (connection == null) {
                    Instance instance = computer.updateInstanceDescription();
                    String host = EC2HostAddressProvider.windows(instance, template.connectionStrategy);

                    // Check when host is null or we will keep trying and receiving a hostname cannot be null forever.
                    if (host == null || "0.0.0.0".equals(host)) {
                        logger.println("Invalid host (null or 0.0.0.0). Your host is most likely waiting for an IP address.");
                        throw new IOException("goto sleep");
                    }

                    if (!node.isSpecifyPassword()) {
                        GetPasswordDataResult result;
                        logger.print(String.format("GetPass sleep range: %ds. ", TimeUnit.MILLISECONDS.toSeconds(sleepBetweenGetPassRange)));
                        logger.print(String.format("GetPass max sleep: %ds. ", TimeUnit.MILLISECONDS.toSeconds(maxGetPassThreadSleep)));
                        try {
                            result = node.getCloud().connect().getPasswordData(new GetPasswordDataRequest(instance.getInstanceId()));
                        } catch (Exception e) {
                            logger.println(String.format("Unexpected Exception: %s. Sleeping %ds.", e.toString(), TimeUnit.MILLISECONDS.toSeconds(sleepBetweenGetPassAttempts)));
                            Thread.sleep(sleepBetweenGetPassAttempts);
                            getPassBackoff();
                            continue;
                        }
                        String passwordData = result.getPasswordData();
                        if (passwordData == null || passwordData.isEmpty()) {
                            logger.println(String.format("Waiting for password to be available. Sleeping %ds.", TimeUnit.MILLISECONDS.toSeconds(sleepBetweenGetPassAttempts)));
                            Thread.sleep(sleepBetweenGetPassAttempts);
                            getPassBackoff();
                            continue;
                        }
                        String password = node.getCloud().getPrivateKey().decryptWindowsPassword(passwordData);
                        String username = System.getProperty(EC2WindowsLauncher.class.getName() + ".amiTemplateUsername", "david_webb6");

                        if (!node.getRemoteAdmin().equals(username)) {
                            logger.println("WARNING: For password retrieval remote admin must be " + username + ", ignoring user provided value");
                        }
                        logger.println("Connecting to " + "(" + host + ") with WinRM as " + username);
                        connection = new WinConnection(host, username, password, allowSelfSignedCertificate);

                    } else { //password Specified
                        logger.println("Connecting to " + "(" + host + ") with WinRM as " + node.getRemoteAdmin());
                        connection = new WinConnection(host, node.getRemoteAdmin(), node.getAdminPassword().getPlainText(), allowSelfSignedCertificate);
                    }
                    resetGetPassBackoff();
                    connection.setUseHTTPS(node.isUseHTTPS());
                }
                logger.print(String.format("WinRM sleep range: %ds. ", TimeUnit.MILLISECONDS.toSeconds(sleepBetweenWinRMRange)));
                logger.print(String.format("WinRM max sleep: %ds. ", TimeUnit.MILLISECONDS.toSeconds(maxWinRMThreadSleep)));
                if (!connection.pingFailingIfSSHHandShakeError()) {
                    logger.println(String.format("Waiting for WinRM to come up. Sleeping %ds.", TimeUnit.MILLISECONDS.toSeconds(sleepBetweenWinRMAttempts)));
                    Thread.sleep(sleepBetweenWinRMAttempts);
                    winRMBackoff();

                    continue;
                }

                if (!alreadyBooted || node.stopOnTerminate) {
                    logger.println("WinRM service responded. Waiting for WinRM service to stabilize on "
                            + node.getDisplayName());
                    Thread.sleep(node.getBootDelay());
                    alreadyBooted = true;
                    logger.println("WinRM should now be ok on " + node.getDisplayName());
                    if (!connection.pingFailingIfSSHHandShakeError()) {
                        logger.println(String.format("WinRM not yet up. Sleeping %ds.", TimeUnit.MILLISECONDS.toSeconds(sleepBetweenWinRMAttempts)));
                        Thread.sleep(sleepBetweenWinRMAttempts);
                        winRMBackoff();
                        continue;
                    }
                }

                logger.println("Connected with WinRM.");
                resetWinRMBackoff();
                return connection; // successfully connected
            } catch (IOException e) {
                if (e instanceof SSLException) {
                    // To avoid reconnecting continuously
                    computer.setTemporarilyOffline(true, OfflineCause.create(Messages._OfflineCause_SSLException()));
                    // avoid waiting and trying again, this connection needs human intervention to change the certificate
                    throw new AmazonClientException("The SSL connection failed while negotiating SSL", e);
                }
                logger.println(String.format("Waiting for WinRM to come up. Sleeping %ds.", TimeUnit.MILLISECONDS.toSeconds(sleepBetweenWinRMAttempts)));
                Thread.sleep(sleepBetweenWinRMAttempts);
                winRMBackoff();
            }
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }

    private void getPassBackoff() {
        long previousSleepBetweenGetPassRange = sleepBetweenGetPassRange;
        sleepBetweenGetPassRange = sleepBetweenGetPassRange * 2;
        sleepBetweenGetPassAttempts = ThreadLocalRandom.current().nextLong(previousSleepBetweenGetPassRange + 1,
                Math.min(maxGetPassThreadSleep, sleepBetweenGetPassRange));
    }

    private void winRMBackoff() {
        long previousSleepBetweenWinRMRange = sleepBetweenWinRMRange;
        sleepBetweenWinRMRange = sleepBetweenWinRMRange * 2;
        sleepBetweenWinRMAttempts = ThreadLocalRandom.current().nextLong(previousSleepBetweenWinRMRange + 1,
                Math.min(maxWinRMThreadSleep, sleepBetweenWinRMRange));
    }

    private void resetGetPassBackoff() {
        sleepBetweenGetPassRange = TimeUnit.SECONDS.toMillis(1);
        sleepBetweenGetPassAttempts = TimeUnit.SECONDS.toMillis(1);
    }

    private void resetWinRMBackoff() {
        sleepBetweenWinRMRange = TimeUnit.SECONDS.toMillis(1);
        sleepBetweenWinRMAttempts = TimeUnit.SECONDS.toMillis(1);
    }

}