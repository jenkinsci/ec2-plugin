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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.plugins.ec2.util.TestSSHUserPrivateKey;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlTextInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mockito;
import org.xml.sax.SAXException;
import software.amazon.awssdk.services.ec2.Ec2Client;

/**
 * @author Kohsuke Kawaguchi
 */
@WithJenkins
class EC2CloudTest {

    private JenkinsRule r;

    private EC2Cloud cloud;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        cloud = new EC2Cloud(
                "us-east-1",
                true,
                "abc",
                "us-east-1",
                null,
                "ghi",
                "3",
                Collections.emptyList(),
                "roleArn",
                "roleSessionName");
        r.jenkins.clouds.add(cloud);
    }

    @Test
    void testConfigRoundtrip() throws Exception {
        r.submit(getConfigForm());
        r.assertEqualBeans(
                cloud,
                r.jenkins.clouds.get(EC2Cloud.class),
                "cloudName,region,useInstanceProfileForCredentials,privateKey,instanceCap,roleArn,roleSessionName");
    }

    @Test
    void testAmazonEC2FactoryGetInstance() {
        EC2Cloud cloud = r.jenkins.clouds.get(EC2Cloud.class);
        Ec2Client connection = cloud.connect();
        assertNotNull(connection);
        assertTrue(Mockito.mockingDetails(connection).isMock());
    }

    @Test
    void testAmazonEC2FactoryWorksIfSessionNameMissing() throws Exception {
        r.jenkins.clouds.replace(new EC2Cloud(
                "us-east-1", true, "abc", "us-east-1", null, "ghi", "3", Collections.emptyList(), "roleArn", null));
        EC2Cloud cloud = r.jenkins.clouds.get(EC2Cloud.class);
        Ec2Client connection = cloud.connect();
        assertNotNull(connection);
        assertTrue(Mockito.mockingDetails(connection).isMock());
    }

    @Test
    void testSessionNameMissingWarning() {
        EC2Cloud actual = r.jenkins.clouds.get(EC2Cloud.class);
        EC2Cloud.DescriptorImpl descriptor = (EC2Cloud.DescriptorImpl) actual.getDescriptor();
        assertThat(descriptor.doCheckRoleSessionName("roleArn", "").kind, is(FormValidation.Kind.WARNING));
        assertThat(descriptor.doCheckRoleSessionName("roleArn", "roleSessionName").kind, is(FormValidation.Kind.OK));
    }

    @Test
    void testSshKeysCredentialsIdRemainsUnchangedAfterUpdatingOtherFields() throws Exception {
        HtmlForm form = getConfigForm();
        HtmlTextInput input = form.getInputByName("_.roleSessionName");

        input.setText("updatedSessionName");
        r.submit(form);
        EC2Cloud actual = r.jenkins.clouds.get(EC2Cloud.class);
        assertEquals("updatedSessionName", actual.getRoleSessionName());
        r.assertEqualBeans(
                cloud,
                actual,
                "cloudName,region,useInstanceProfileForCredentials,sshKeysCredentialsId,instanceCap,roleArn");
    }

    @Test
    void testAWSCredentials() {
        EC2Cloud actual = r.jenkins.clouds.get(EC2Cloud.class);
        EC2Cloud.DescriptorImpl descriptor = (EC2Cloud.DescriptorImpl) actual.getDescriptor();
        assertNotNull(descriptor);
        ListBoxModel m = descriptor.doFillCredentialsIdItems(Jenkins.get());
        assertThat(m.size(), is(1));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new AWSCredentialsImpl(
                        CredentialsScope.SYSTEM, "system_id", "system_ak", "system_sk", "system_desc"));
        // Ensure added credential is displayed
        m = descriptor.doFillCredentialsIdItems(Jenkins.get());
        assertThat(m.size(), is(2));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new AWSCredentialsImpl(
                        CredentialsScope.GLOBAL, "global_id", "global_ak", "global_sk", "global_desc"));
        m = descriptor.doFillCredentialsIdItems(Jenkins.get());
        assertThat(m.size(), is(3));
    }

    @Test
    void testSshCredentials() throws IOException {
        EC2Cloud actual = r.jenkins.clouds.get(EC2Cloud.class);
        EC2Cloud.DescriptorImpl descriptor = (EC2Cloud.DescriptorImpl) actual.getDescriptor();
        assertNotNull(descriptor);
        ListBoxModel m = descriptor.doFillSshKeysCredentialsIdItems(Jenkins.get(), "");
        assertThat(m.size(), is(1));
        BasicSSHUserPrivateKey sshKeyCredentials = new BasicSSHUserPrivateKey(
                CredentialsScope.SYSTEM,
                "ghi",
                "key",
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource("somekey"),
                "",
                "");
        for (CredentialsStore credentialsStore : CredentialsProvider.lookupStores(r.jenkins)) {
            if (credentialsStore instanceof SystemCredentialsProvider.StoreImpl) {
                credentialsStore.addCredentials(Domain.global(), sshKeyCredentials);
            }
        }
        // Ensure added credential is displayed
        m = descriptor.doFillSshKeysCredentialsIdItems(Jenkins.get(), "");
        assertThat(m.size(), is(2));
        // Ensure that the cloud can resolve the new key
        assertThat(actual.resolvePrivateKey(), notNullValue());
    }

    /**
     * Ensure that EC2 plugin can use any implementation of SSHUserPrivateKey (not just the default implementation, BasicSSHUserPrivateKey).
     */
    @Test
    @Issue("JENKINS-63986")
    void testCustomSshCredentialTypes() throws IOException {
        EC2Cloud actual = r.jenkins.clouds.get(EC2Cloud.class);
        EC2Cloud.DescriptorImpl descriptor = (EC2Cloud.DescriptorImpl) actual.getDescriptor();
        assertNotNull(descriptor);
        ListBoxModel m = descriptor.doFillSshKeysCredentialsIdItems(Jenkins.get(), "");
        assertThat(m.size(), is(1));
        SSHUserPrivateKey sshKeyCredentials = new TestSSHUserPrivateKey(
                CredentialsScope.SYSTEM,
                "ghi",
                "key",
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource("somekey"),
                "",
                "");
        for (CredentialsStore credentialsStore : CredentialsProvider.lookupStores(r.jenkins)) {
            if (credentialsStore instanceof SystemCredentialsProvider.StoreImpl) {
                credentialsStore.addCredentials(Domain.global(), sshKeyCredentials);
            }
        }
        // Ensure added credential is displayed
        m = descriptor.doFillSshKeysCredentialsIdItems(Jenkins.get(), "");
        assertThat(m.size(), is(2));
        // Ensure that the cloud can resolve the new key
        assertThat(actual.resolvePrivateKey(), notNullValue());
    }

    @Test
    public void testCloudNameForCasC() {
        EC2Cloud cloud = new EC2Cloud("test-cloud", false, null, "us-east-1", null, null, null, Collections.emptyList(), null, null);
        
        // Test that getCloudName returns the name
        assertEquals("test-cloud", cloud.getCloudName());
        assertEquals("test-cloud", cloud.name);
        
        // Test that setCloudName updates the name field
        cloud.setCloudName("my-ec2-cloud");
        assertEquals("my-ec2-cloud", cloud.name);
        assertEquals("my-ec2-cloud", cloud.getCloudName());
        
        // Test that cloudName is the primary field for CasC configurations
        cloud.setCloudName("production-ec2");
        assertEquals("production-ec2", cloud.getCloudName());
    }

    @Test
    public void testCloudNameConstructorParameter() {
        // Test that cloudName constructor parameter works directly
        EC2Cloud cloud = new EC2Cloud("my-casc-cloud", false, null, "us-east-1", null, null, null, Collections.emptyList(), null, null);
        
        // Constructor parameter sets the name directly
        assertEquals("my-casc-cloud", cloud.name);
        assertEquals("my-casc-cloud", cloud.getCloudName());
    }

    private HtmlForm getConfigForm() throws IOException, SAXException {
        return r.createWebClient().goTo(cloud.getUrl() + "configure").getFormByName("config");
    }
}
