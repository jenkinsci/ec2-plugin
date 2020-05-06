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
package hudson.plugins.ec2;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.plugins.ec2.ssh.verifiers.AcceptNewStrategy;
import hudson.plugins.ec2.ssh.verifiers.CheckNewHardStrategy;
import hudson.plugins.ec2.ssh.verifiers.CheckNewSoftStrategy;
import hudson.plugins.ec2.ssh.verifiers.NonVerifyingKeyVerificationStrategy;
import hudson.plugins.ec2.ssh.verifiers.SshHostKeyVerificationStrategy;

public enum HostKeyVerificationStrategyEnum {
    CHECK_NEW_HARD("check-new-hard", "yes", new CheckNewHardStrategy()),
    CHECK_NEW_SOFT("check-new-soft", "accept-new", new CheckNewSoftStrategy()),
    ACCEPT_NEW("accept-new", "accept-new", new AcceptNewStrategy()),
    OFF("off", "off", new NonVerifyingKeyVerificationStrategy());
    
    private final String displayText;
    private final SshHostKeyVerificationStrategy strategy;
    private final String sshCommandEquivalentFlag;
    
    HostKeyVerificationStrategyEnum(@NonNull String displayText, @NonNull String sshCommandEquivalentFlag, @NonNull SshHostKeyVerificationStrategy strategy) {
        this.displayText = displayText;
        this.sshCommandEquivalentFlag = sshCommandEquivalentFlag;
        this.strategy = strategy;
    }

    @NonNull
    public SshHostKeyVerificationStrategy getStrategy() {
        return strategy;
    }
    
    public boolean equalsDisplayText(String other) {
        return this.displayText.equals(other);
    }

    @NonNull
    public String getDisplayText() {
        return displayText;
    }

    @NonNull
    public String getSshCommandEquivalentFlag() {
        return sshCommandEquivalentFlag;
    }
}
