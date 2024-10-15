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
import hudson.model.ItemGroup;
import hudson.util.FormValidation;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import javax.servlet.ServletException;

import hudson.util.Secret;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Eucalyptus.
 *
 * @author Kohsuke Kawaguchi
 */
public class Eucalyptus extends EC2Cloud {
    private final URL ec2endpoint;
    private final URL s3endpoint;

    @DataBoundConstructor
    public Eucalyptus(String name, URL ec2EndpointUrl, URL s3EndpointUrl, boolean useInstanceProfileForCredentials, String credentialsId, String privateKey, Secret sshPrivateKeySecret, String sshKeysCredentialsId, String instanceCapStr, List<SlaveTemplate> templates, String roleArn, String roleSessionName) {
        super(name, useInstanceProfileForCredentials, credentialsId, privateKey, sshPrivateKeySecret, sshKeysCredentialsId, instanceCapStr, templates, roleArn, roleSessionName);
        this.ec2endpoint = ec2EndpointUrl;
        this.s3endpoint = s3EndpointUrl;
    }

    @Deprecated
    public Eucalyptus(URL ec2EndpointUrl, URL s3EndpointUrl, boolean useInstanceProfileForCredentials, String credentialsId, String privateKey, String sshKeysCredentialsId, String instanceCapStr, List<SlaveTemplate> templates, String roleArn, String roleSessionName)
            throws IOException {
        this("eucalyptus", ec2EndpointUrl, s3EndpointUrl, useInstanceProfileForCredentials, credentialsId, privateKey, null, sshKeysCredentialsId, instanceCapStr, templates, roleArn, roleSessionName);
    }

    @Deprecated
    public Eucalyptus(URL ec2EndpointUrl, URL s3EndpointUrl, boolean useInstanceProfileForCredentials, String credentialsId, String privateKey, String instanceCapStr, List<SlaveTemplate> templates, String roleArn, String roleSessionName)            throws IOException {
        this("eucalyptus", ec2EndpointUrl, s3EndpointUrl, useInstanceProfileForCredentials, credentialsId, privateKey, null,null, instanceCapStr, templates, roleArn, roleSessionName);
    }

    @Override
    public URL getEc2EndpointUrl() throws IOException {
        return this.ec2endpoint;
    }

    @Override
    public URL getS3EndpointUrl() throws IOException {
        return this.s3endpoint;
    }

    @Extension
    public static class DescriptorImpl extends EC2Cloud.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return "Eucalyptus";
        }

        @Override
        @RequirePOST
        public FormValidation doTestConnection(@AncestorInPath ItemGroup context, @QueryParameter URL ec2endpoint, @QueryParameter boolean useInstanceProfileForCredentials, @QueryParameter String credentialsId, @QueryParameter String sshKeysCredentialsId, @QueryParameter String roleArn, @QueryParameter String roleSessionName, @QueryParameter String region, @QueryParameter Secret sshPrivateKeySecret)
                throws IOException, ServletException {
            return super.doTestConnection(context, ec2endpoint, useInstanceProfileForCredentials, credentialsId, sshKeysCredentialsId, roleArn, roleSessionName, region, sshPrivateKeySecret);
        }
    }
}
