/*
 * The MIT License
 *
 * Copyright (c) 2020-, M Ramon Leon, CloudBees, Inc.
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
package hudson.plugins.ec2.ssh.verifiers;

import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.slaves.OfflineCause;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This strategy accepts any new host key and stores it in a known_hosts file inside the node directory called
 * ssh-host-key.xml. Every next attempt to connect to the node, the key is checked against the one stored. It's the
 * same approach as the accept-new value of the StrictHostKeyChecking flag of ssh_config.
 * @author M Ramon Leon
 * @since TODO
 */
public class AcceptNewStrategy extends SshHostKeyVerificationStrategy {
    private static final Logger LOGGER = Logger.getLogger(AcceptNewStrategy.class.getName());

    @Override
    public boolean verify(EC2Computer computer, HostKey hostKey, TaskListener listener) throws IOException {
        HostKey existingHostKey = HostKeyHelper.getInstance().getHostKey(computer);
        if (null == existingHostKey) {
            HostKeyHelper.getInstance().saveHostKey(computer, hostKey);
            EC2Cloud.log(
                    LOGGER,
                    Level.INFO,
                    computer.getListener(),
                    String.format(
                            "The SSH key %s %s has been automatically trusted for connections to %s",
                            hostKey.getAlgorithm(), hostKey.getFingerprint(), computer.getName()));
            return true;
        } else if (existingHostKey.equals(hostKey)) {
            EC2Cloud.log(
                    LOGGER,
                    Level.INFO,
                    computer.getListener(),
                    String.format("Connection allowed after the host key has been verified"));
            return true;
        } else {
            EC2Cloud.log(
                    LOGGER,
                    Level.WARNING,
                    computer.getListener(),
                    String.format(
                            "The SSH key (%s) presented by the instance has changed since first saved (%s). The connection to %s is closed to prevent a possible man-in-the-middle attack",
                            hostKey.getFingerprint(), existingHostKey.getFingerprint(), computer.getName()));
            // To avoid reconnecting continuously
            computer.setTemporarilyOffline(true, OfflineCause.create(Messages._OfflineCause_SSHKeyCheckFailed()));
            return false;
        }
    }
}
