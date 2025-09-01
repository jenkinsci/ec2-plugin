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
package hudson.plugins.ec2;

import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;
import java.util.List;

/**
 * @deprecated use {@link EC2Cloud}
 */
@Deprecated
public class AmazonEC2Cloud extends EC2Cloud {
    @DataBoundConstructor
    public AmazonEC2Cloud(
            String name,
            boolean useInstanceProfileForCredentials,
            String credentialsId,
            String region,
            String privateKey,
            String sshKeysCredentialsId,
            String instanceCapStr,
            List<? extends SlaveTemplate> templates,
            String roleArn,
            String roleSessionName) {
        super(
                name,
                useInstanceProfileForCredentials,
                credentialsId,
                region,
                privateKey,
                sshKeysCredentialsId,
                instanceCapStr,
                templates,
                roleArn,
                roleSessionName);
    }

    public AmazonEC2Cloud(
            String name,
            boolean useInstanceProfileForCredentials,
            String credentialsId,
            String region,
            String privateKey,
            String instanceCapStr,
            List<? extends SlaveTemplate> templates,
            String roleArn,
            String roleSessionName) {
        super(
                name,
                useInstanceProfileForCredentials,
                credentialsId,
                region,
                privateKey,
                instanceCapStr,
                templates,
                roleArn,
                roleSessionName);
    }

    @Extension
    public static class DescriptorImpl extends EC2Cloud.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return "Amazon EC2 (Deprecated - use EC2 instead)";
        }
    }
}
