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
import hudson.plugins.ec2.util.KeyHelper;
import java.io.IOException;
import java.security.PublicKey;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This strategy doesn't perform any kind of verification
 * @author M Ramon Leon
 * @since TODO
 */
public class NonVerifyingKeyVerificationStrategy extends SshHostKeyVerificationStrategy {
    private static final Logger LOGGER = Logger.getLogger(NonVerifyingKeyVerificationStrategy.class.getName());

    @Override
    public boolean verify(EC2Computer computer, PublicKey serverKey, TaskListener listener) throws Exception {
        EC2Cloud.log(
                LOGGER,
                Level.INFO,
                computer.getListener(),
                String.format(
                        "No SSH key verification (%s %s) for connections to %s",
                        KeyHelper.getSshAlgorithm(serverKey), KeyHelper.getFingerprint(serverKey), computer.getName()));
        return true;
    }

    @Override
    public boolean verify(EC2Computer computer, HostKey hostKey, TaskListener listener) throws IOException {
        EC2Cloud.log(
                LOGGER,
                Level.INFO,
                computer.getListener(),
                String.format(
                        "No SSH key verification (%s %s) for connections to %s",
                        hostKey.getAlgorithm(), hostKey.getFingerprint(), computer.getName()));
        return true;
    }
}
