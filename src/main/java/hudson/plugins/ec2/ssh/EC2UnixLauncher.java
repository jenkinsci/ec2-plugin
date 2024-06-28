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
import com.trilead.ssh2.Connection;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Computer;
import hudson.slaves.ComputerLauncher;

import java.io.IOException;
import java.io.PrintStream;

/**
 * {@link ComputerLauncher} that connects to a Unix agent on EC2 by using SSH.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends EC2SSHLauncher {

    @Override
    protected void runAmiTypeSpecificLaunchScript(EC2Computer computer, String javaPath, Connection conn, PrintStream logger, TaskListener listener) throws IOException, AmazonClientException, InterruptedException {
        executeRemote(computer, conn, javaPath + " -fullversion", "sudo amazon-linux-extras install java-openjdk11 -y; sudo yum install -y fontconfig java-11-openjdk", logger, listener);
        executeRemote(computer, conn, "which scp", "sudo yum install -y openssh-clients", logger, listener);
    }
}
