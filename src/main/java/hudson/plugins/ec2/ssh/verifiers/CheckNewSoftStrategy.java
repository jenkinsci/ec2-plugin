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
 * This strategy checks the key presented by the host with the one printed out in the instance console. The key should
 * be printed with the format "algorithm key". Example: ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJbvbEIoY3tqKwkeRW/L1FnbCLLp8a1TwSOyZHKJqFFR
 * If the key is not found because the console is blank, the connection is closed and wait until console prints something.
 * If the key is not found because the instance doesn't print any key, the connection is trusted.
 * If it's found and the key presented by the instance doesn't match the one printed in the console, the connection is closed
 * and a warning is logged.
 * If the key is found, it's stored to check future connections.
 * @author M Ramon Leon
 * @since TODO
 */
public class CheckNewSoftStrategy extends SshHostKeyVerificationStrategy {
    private static final Logger LOGGER = Logger.getLogger(CheckNewSoftStrategy.class.getName());

    @Override
    public boolean verify(EC2Computer computer, HostKey hostKey, TaskListener listener) throws IOException {
        HostKey existingHostKey = HostKeyHelper.getInstance().getHostKey(computer);
        if (null == existingHostKey) {
            HostKey consoleHostKey = getHostKeyFromConsole(LOGGER, computer, hostKey.getAlgorithm());

            if (hostKey.equals(consoleHostKey)) {
                HostKeyHelper.getInstance().saveHostKey(computer, hostKey);
                EC2Cloud.log(
                        LOGGER,
                        Level.INFO,
                        computer.getListener(),
                        String.format(
                                "The SSH key %s %s has been successfully checked against the instance console for connections to %s",
                                hostKey.getAlgorithm(), hostKey.getFingerprint(), computer.getName()));
                return true;
            } else if (consoleHostKey == null) {
                EC2Cloud.log(
                        LOGGER,
                        Level.INFO,
                        computer.getListener(),
                        String.format(
                                "The instance console is blank. Cannot check the key. The connection to %s is not allowed",
                                computer.getName()));
                return false; // waiting for next retry to have the console filled up
            } else if (consoleHostKey.getKey().length == 0) {
                EC2Cloud.log(
                        LOGGER,
                        Level.INFO,
                        computer.getListener(),
                        String.format(
                                "The SSH key (%s %s) presented by the instance has not been found on the instance console. Cannot check the key but the connection to %s is allowed",
                                hostKey.getAlgorithm(), hostKey.getFingerprint(), computer.getName()));
                // it is the difference with the the hard strategy, the key is accepted
                HostKeyHelper.getInstance().saveHostKey(computer, hostKey);
                return true;
            } else {
                EC2Cloud.log(
                        LOGGER,
                        Level.WARNING,
                        computer.getListener(),
                        String.format(
                                "The SSH key (%s %s) presented by the instance is different from the one printed out on the instance console (%s %s). The connection to %s is closed to prevent a possible man-in-the-middle attack",
                                hostKey.getAlgorithm(),
                                hostKey.getFingerprint(),
                                consoleHostKey.getAlgorithm(),
                                consoleHostKey.getFingerprint(),
                                computer.getName()));
                // To avoid reconnecting continuously
                computer.setTemporarilyOffline(true, OfflineCause.create(Messages._OfflineCause_SSHKeyCheckFailed()));
                return false;
            }
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
