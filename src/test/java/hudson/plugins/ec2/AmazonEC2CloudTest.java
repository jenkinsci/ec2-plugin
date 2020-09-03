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

import com.amazonaws.services.ec2.AmazonEC2;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlTextInput;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;
import org.xml.sax.SAXException;

import hudson.util.VersionNumber;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * @author Kohsuke Kawaguchi
 */
public class AmazonEC2CloudTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private AmazonEC2Cloud cloud;

    @Before
    public void setUp() throws Exception {
        cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", null, "ghi", "3", Collections.emptyList(), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(cloud);
    }

    @Test
    public void testConfigRoundtrip() throws Exception {
        r.submit(getConfigForm());
        r.assertEqualBeans(cloud, r.jenkins.clouds.get(AmazonEC2Cloud.class), "cloudName,region,useInstanceProfileForCredentials,privateKey,instanceCap,roleArn,roleSessionName");
    }

    @Test
    public void testAmazonEC2FactoryGetInstance() throws Exception {
        r.configRoundtrip();
        AmazonEC2Cloud cloud = r.jenkins.clouds.get(AmazonEC2Cloud.class);
        AmazonEC2 connection = cloud.connect();
        Assert.assertNotNull(connection);
        Assert.assertTrue(Mockito.mockingDetails(connection).isMock());
    }

    @Test
    public void testSshKeysCredentialsIdRemainsUnchangedAfterUpdatingOtherFields() throws Exception {
        HtmlForm form = getConfigForm();
        HtmlTextInput input = form.getInputByName("_.cloudName");

        input.setText("test-cloud-2");
        r.submit(form);
        AmazonEC2Cloud actual = r.jenkins.clouds.get(AmazonEC2Cloud.class);
        assertEquals("test-cloud-2", actual.getCloudName());
        r.assertEqualBeans(cloud, actual, "region,useInstanceProfileForCredentials,sshKeysCredentialsId,instanceCap,roleArn,roleSessionName");
    }

    private HtmlForm getConfigForm() throws IOException, SAXException {
        if (Jenkins.getVersion().isNewerThanOrEqualTo(new VersionNumber("2.205"))) {
            return r.createWebClient().goTo("configureClouds").getFormByName("config");
        } else {
            return r.createWebClient().goTo("configure").getFormByName("config");
        }
    }

}