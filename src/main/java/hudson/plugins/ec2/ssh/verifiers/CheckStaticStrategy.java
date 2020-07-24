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

import java.util.ArrayList;
import java.util.Base64;

import java.io.IOException;
import java.util.Scanner;
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
public class CheckStaticStrategy extends SshHostKeyVerificationStrategy {
    private static final Logger LOGGER = Logger.getLogger(CheckStaticStrategy.class.getName());

    private ArrayList<HostKey> getStaticHostKeys(EC2Computer computer) {
        ArrayList<HostKey> hostKeys = new ArrayList<>();

        Scanner scanner = new Scanner(computer.getSlaveTemplate().getStaticHostKeys());
        while (scanner.hasNextLine()) {
            String hostKeyString = scanner.nextLine();
            String[] hostKeyParts = hostKeyString.split(" ");
            if (hostKeyParts.length != 2) {
                EC2Cloud.log(LOGGER, Level.WARNING, computer.getListener(), "invalid static SSH key");
                continue;
            }
            HostKey hostKey = new HostKey(hostKeyParts[0], Base64.getDecoder().decode(hostKeyParts[1]));
            hostKeys.add(hostKey);
        }
        scanner.close();
        return hostKeys;
    }

    @Override
    public boolean verify(EC2Computer computer, HostKey hostKey, TaskListener listener) throws IOException {
        HostKey existingHostKey = HostKeyHelper.getInstance().getHostKey(computer);
        ArrayList<HostKey> staticHostKeys = getStaticHostKeys(computer);

        if (staticHostKeys.size() < 1) {
            EC2Cloud.log(LOGGER, Level.WARNING, computer.getListener(), "No static SSH keys found");
            // To avoid reconnecting continuously
            computer.setTemporarilyOffline(true, OfflineCause.create(Messages._OfflineCause_SSHKeyCheckFailed()));
            return false;
        }

        if (null == existingHostKey) {
            for (HostKey staticHostKey : staticHostKeys) {
                if (hostKey.equals(staticHostKey)) {
                    HostKeyHelper.getInstance().saveHostKey(computer, hostKey);
                    EC2Cloud.log(LOGGER, Level.INFO, computer.getListener(), String.format("The SSH key %s %s has been successfully checked against the instance console for connections to %s", hostKey.getAlgorithm(), hostKey.getFingerprint(), computer.getName()));
                    return true;
                }
            }
            // To avoid reconnecting continuously
            computer.setTemporarilyOffline(true, OfflineCause.create(Messages._OfflineCause_SSHKeyCheckFailed()));
            return false;

        } else if (existingHostKey.equals(hostKey)) {
            EC2Cloud.log(LOGGER, Level.INFO, computer.getListener(), String.format("Connection allowed after the host key has been verified"));
            return true;
        } else {
            EC2Cloud.log(LOGGER, Level.WARNING, computer.getListener(), String.format("The SSH key (%s) presented by the instance has changed since first saved (%s). The connection to %s is closed to prevent a possible man-in-the-middle attack", hostKey.getFingerprint(), existingHostKey.getFingerprint(), computer.getName()));
            // To avoid reconnecting continuously
            computer.setTemporarilyOffline(true, OfflineCause.create(Messages._OfflineCause_SSHKeyCheckFailed()));
            return false;
        }
    }
}
