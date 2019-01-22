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

import hudson.slaves.Cloud;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;

import static hudson.plugins.ec2.EC2Cloud.DEFAULT_EC2_ENDPOINT;
import static org.junit.Assert.assertEquals;

/**
 * @author Kohsuke Kawaguchi
 */
public class AmazonEC2CloudTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Before
    public void setUp() throws Exception {
        AmazonEC2Cloud.testMode = true;
    }

    @After
    public void tearDown() throws Exception {
        AmazonEC2Cloud.testMode = false;
    }

    @After
    public void testConfigRoundtrip() throws Exception {
        AmazonEC2Cloud orig = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1", "ghi", "3", Collections.emptyList(), "roleArn", "roleSessionName");
        r.jenkins.clouds.add(orig);
        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));

        Cloud actual = r.jenkins.clouds.iterator().next();
        r.assertEqualBeans(orig, actual, "cloudName,region,useInstanceProfileForCredentials,accessId,privateKey,instanceCap,roleArn,roleSessionName");
    }

    /**
     * Unit tests related to {@link AmazonEC2Cloud}, but do not require a Jenkins instance.
     */
    public static class UnitTests {
        @Test
        public void testEC2EndpointURLCreation() throws MalformedURLException {
            AmazonEC2Cloud.DescriptorImpl descriptor = new AmazonEC2Cloud.DescriptorImpl();

            assertEquals(new URL(DEFAULT_EC2_ENDPOINT), descriptor.determineEC2EndpointURL(null));
            assertEquals(new URL(DEFAULT_EC2_ENDPOINT), descriptor.determineEC2EndpointURL(""));
            assertEquals(new URL("https://www.abc.com"), descriptor.determineEC2EndpointURL("https://www.abc.com"));
        }
    }
}
