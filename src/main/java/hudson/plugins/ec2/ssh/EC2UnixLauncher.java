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

import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.slaves.ComputerLauncher;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import jenkins.model.Jenkins;
import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.KeyPair;

/**
 * {@link ComputerLauncher} that connects to a Unix slave on EC2 by using SSH.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends EC2ComputerLauncher {

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

    protected String buildUpCommand(EC2Computer computer, String command) {
        if (!computer.getRemoteAdmin().equals("root")) {
            command = computer.getRootCommandPrefix() + " " + command;
        }
        return command;
    }

    @Override
    protected void launch(EC2Computer computer, TaskListener listener, Instance inst) throws IOException,
            AmazonClientException, InterruptedException {
        EC2Logger log = new EC2Logger(listener);

        PrintStream logger = listener.getLogger();
        log.info("Launching instance: " + computer.getNode().getInstanceId());

        SshClient sshClient = null;

        try {
            //this will internally call "connect() on the ssh and throw exception if we failed in the end
            sshClient = createSshClient(computer, log);

            String initScript = computer.getNode().initScript;
            String tmpDir = (Util.fixEmptyAndTrim(computer.getNode().tmpDir) != null ?
                    computer.getNode().tmpDir : "/tmp");

            log.info("Creating tmp directory (" + tmpDir + ") if it does not exist");
            sshClient.run("mkdir -p " + tmpDir, logger);

            if (initScript != null && initScript.trim().length() > 0
                    && sshClient.run("test -e ~/.hudson-run-init", logger) != 0) {
                log.info("Executing init script");
                sshClient.put(initScript.getBytes("UTF-8"), "init.sh", tmpDir, "0700");

                sshClient.run(buildUpCommand(computer, tmpDir + "/init.sh"), logger);

                sshClient.run(buildUpCommand(computer, "touch ~/.hudson-run-init"), logger);
            }

            // TODO: parse the version number. maven-enforcer-plugin might help
            log.info("Verifying that java exists");
            if (sshClient.run("java -fullversion", logger) != 0) {
                log.info("Installing Java");

                String jdk = "java1.6.0_12";
                String path = "/hudson-ci/jdk/linux-i586/" + jdk + ".tgz";

                URL url = computer.getCloud().buildPresignedURL(path);
                if (sshClient.run("wget -nv -O " + tmpDir + "/" + jdk + ".tgz '" + url + "'", logger) != 0) {
                    log.warn("Failed to download Java");
                    return;
                }

                if (sshClient.run(buildUpCommand(computer, "tar xz -C /usr -f " + tmpDir + "/" + jdk + ".tgz"), logger) != 0) {
                    log.warn("Failed to install Java");
                    return;
                }

                if (sshClient.run(buildUpCommand(computer, "ln -s /usr/" + jdk + "/bin/java /bin/java"), logger) != 0) {
                    log.warn("Failed to symlink Java");
                    return;
                }
            }

            // TODO: on Windows with ec2-sshd, this scp command ends up just
            // putting slave.jar as c:\tmp
            // bug in ec2-sshd?

            log.info("Copying slave.jar");
            sshClient.put(Jenkins.getInstance().getJnlpJars("slave.jar").readFully(), "slave.jar", tmpDir, "0600");

            String jvmopts = computer.getNode().jvmopts;
            String launchString = "java " + (jvmopts != null ? jvmopts : "") + " -jar " + tmpDir + "/slave.jar";

            sshClient.startCommandPipe(launchString, computer, listener);
        } catch (Exception e) {
            if (sshClient != null)
                sshClient.close();
        }
    }

    private SshClient createSshClient(EC2Computer computer, EC2Logger logger) throws AmazonClientException, InterruptedException {
        final long timeout = computer.getNode().getLaunchTimeoutInMillis();
        final long startTime = System.currentTimeMillis();
        int tries = bootstrapAuthTries;

        //note that we are redoing a lot on every iteration because many details (such as key info, etc)
        // may not be available at the initial launch stages
        while (tries-- > 0) {
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
                    logger.warn("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                int port = computer.getSshPort();
                Integer slaveConnectTimeout = Integer.getInteger("jenkins.ec2.slaveConnectTimeout", 10000);
                logger.info("Connecting to " + host + " on port " + port + ", with timeout "
                        + slaveConnectTimeout + ". Left attempts="+tries+" time="+(timeout - waitTime));

                logger.info("Getting keypair...");
                KeyPair key = computer.getCloud().getKeyPair();
                logger.info("Using key: " + key.getKeyName() + "\n" + key.getKeyFingerprint() + "\n"
                        + key.getKeyMaterial().substring(0, 160));

                SshClient ssh;
                SlaveTemplate slaveTemplate = computer.getSlaveTemplate();

                if (slaveTemplate != null && slaveTemplate.isConnectBySSHProcess()) {
                    logger.info("Using system SSH client");
                    ssh = new SystemSSHClient(
                            logger,
                            host,
                            port,
                            slaveConnectTimeout,
                            computer.getRemoteAdmin(),
                            computer.getCloud().getKeyPair().getKeyMaterial().toCharArray());
                } else {
                    logger.info("Using Trilead SSH client");
                    ssh = new TrileadSshClient(
                            logger,
                            host,
                            port,
                            slaveConnectTimeout,
                            computer.getRemoteAdmin(),
                            computer.getCloud().getKeyPair().getKeyMaterial().toCharArray());
                }

                ssh.connect();

                return ssh; // successfully connected
            } catch (IOException e) {
                // keep retrying until SSH comes up
                logger.info("Failed to connect via ssh: " + e.getMessage());
                logger.info("Waiting for SSH to come up. Sleeping " + bootstrapAuthSleepMs + ".");
                Thread.sleep(bootstrapAuthSleepMs);
            }
        }

        return null;
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


    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
