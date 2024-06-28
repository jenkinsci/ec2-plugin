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

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;
import com.trilead.ssh2.Connection;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Level;

/**
 * {@link ComputerLauncher} that connects to a Unix agent on EC2 by using SSH.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2MacLauncher extends EC2SSHLauncher {


    @Override
    protected void runAmiTypeSpecificLaunchScript(EC2Computer computer, String javaPath, Connection conn, PrintStream logger, TaskListener listener) throws IOException, AmazonClientException {
        // TODO: parse the version number. maven-enforcer-plugin might help
        try {
            Instance nodeInstance = computer.describeInstance();
            if (nodeInstance.getInstanceType().equals("mac2.metal")) {
                LOGGER.info("Running Command for mac2.metal");
                executeRemote(computer, conn, javaPath + " -fullversion", "curl -L -O https://corretto.aws/downloads/latest/amazon-corretto-11-aarch64-macos-jdk.pkg; sudo installer -pkg amazon-corretto-11-aarch64-macos-jdk.pkg -target /", logger, listener);
            } else {
                executeRemote(computer, conn, javaPath + " -fullversion", "curl -L -O https://corretto.aws/downloads/latest/amazon-corretto-11-x64-macos-jdk.pkg; sudo installer -pkg amazon-corretto-11-x64-macos-jdk.pkg -target /", logger, listener);
            }
        } catch (InterruptedException ex) {
            LOGGER.warning(ex.getMessage());
        }
    }

    @Override
    protected void configureConnectBySSHProcess(EC2Computer computer, TaskListener listener, SlaveTemplate slaveTemplate, EC2AbstractSlave node, String launchString) throws IOException, InterruptedException {
        File identityKeyFile = createIdentityKeyFile(computer);
        String ec2HostAddress = getEC2HostAddress(computer, slaveTemplate);
        File hostKeyFile;
        String userKnownHostsFileFlag = "";
        if (!slaveTemplate.amiType.isMac()) {
            hostKeyFile = createHostKeyFile(computer, ec2HostAddress, listener);
            if (hostKeyFile != null) {
                userKnownHostsFileFlag = String.format(" -o \"UserKnownHostsFile=%s\"", hostKeyFile.getAbsolutePath());
            }
        }

        try {
            // Obviously the controller must have an installed ssh client.
            // Depending on the strategy selected on the UI, we set the StrictHostKeyChecking flag
            String sshClientLaunchString = String.format("ssh -o StrictHostKeyChecking=%s%s%s -i %s %s@%s -p %d %s", slaveTemplate.getHostKeyVerificationStrategy().getSshCommandEquivalentFlag(), userKnownHostsFileFlag, getEC2HostKeyAlgorithmFlag(computer), identityKeyFile.getAbsolutePath(), node.remoteAdmin, ec2HostAddress, node.getSshPort(), launchString);
            logInfo(computer, listener, "Launching remoting agent (via SSH client process): " + sshClientLaunchString);
            CommandLauncher commandLauncher = new CommandLauncher(sshClientLaunchString, null);
            commandLauncher.launch(computer, listener);
        } finally {
            if(!identityKeyFile.delete()) {
                LOGGER.log(Level.WARNING, "Failed to delete identity key file");
            }
        }
    }
}
