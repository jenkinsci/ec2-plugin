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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.*;

import com.amazonaws.services.ec2.model.InstanceType;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.plugins.credentials.*;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import hudson.model.Node;
import hudson.plugins.ec2.util.CredentialStoreNotFoundException;
import hudson.plugins.ec2.util.CredentialUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Basic test to validate SlaveTemplate.
 */
public class SlaveTemplateTest {
    private static final String TEST_PRIVATE_SSH_KEY =
        "-----BEGIN RSA PRIVATE KEY-----\n" +
        "MIICXAIBAAKBgQDOi1No7rKb/2c6/K7xUv1I3Zy5duoJENm8c/OZ2oaMRmThkJ2s\n" +
        "pSznT1+weJwmCiEgF8hF1vVk7OduSinc+sRa/AuQ7IuK87/VOjAOjhTsAc+T/Dkk\n" +
        "XJjVp/mSGJmr1xoP1GsJI+AnGuo9+unmOVl7l3L+ZKuKeEK7tDoGb3fAXQIDAQAB\n" +
        "AoGBAJ9qa/OGoLbE11Fw7Dn4+uN9oNSJErPynIvW1wM95jFot75dl0VEq7bQzaNw\n" +
        "Q90cXlrd4Eb/VaITM8EtXshfiKLpFKEmtuj4ZPm7lagEotWNT212ZPBl+oMY77KL\n" +
        "XR8G8S2Dme33J8uCaAZBNwfvbJpQg+PLUCrEj0gt+qbjy/2BAkEA91kTrH4oAz7V\n" +
        "DpUseSgRn4T1hmNsOQKbrdsXyR2rsm8UNJyAnVq5WCn5rlNFH/qMQOmFmDHJpupK\n" +
        "dzvLM1OFfQJBANXE3VI7JkgvkZcHEFPb/nPwctCyozeg+HcfF8CidQBLJU13oNFa\n" +
        "rNrmPblG2NvYw60hJeyMGpssInueXeaCnGECQDoo9dlPaLUqpwpgxS5P36T0rI7G\n" +
        "/gGBvX1p0PP3SBIS0Ft2mT9mv8IdTJpS9iQI08XHoyQgQNxApvXWV3dgIjkCQBz3\n" +
        "1YocK97iW1ddBLBogn3Rmq1/V7DlJmZ2FzDqkvJcPIzX5joYkI4FX13pJN/96t5e\n" +
        "PJZmkgBvJakc19qx3mECQG6S23/tHJ9Mc7tvCv26ncf4X/ZEbctIG9AoHSH7HfBu\n" +
        "ldJiiRUiL4X8eWuVuRVtHkyD9b6G1iHeIE98a9+ICm0=\n" +
        "-----END RSA PRIVATE KEY-----\n";

    public WireMockRule wireMockRule = new WireMockRule(
        WireMockConfiguration.options()
            .dynamicPort()
            .withRootDirectory("src/test/resources/" +
                getClass().getPackage().getName().replace(".", "/")));
    public JenkinsRule r = new JenkinsRule();

    @Rule
    public RuleChain ruleChain = RuleChain
        .outerRule(wireMockRule)
        .around(r);

    @Before
    public void setUp() throws Exception {
        AmazonEC2Cloud.setTestMode(true);
        AmazonEC2Cloud.setEc2TestEndpointUrl(wireMockRule.url("/ec2"));

        stubFor(
            post(urlEqualTo("/ec2/"))
                .withRequestBody(containing("Action=DescribeImages"))
                .willReturn(ok().withBodyFile("ec2Responses/DescribeImages.xml"))
        );
        stubFor(
            post(urlEqualTo("/ec2/"))
                .withRequestBody(containing("Action=DescribeKeyPairs"))
                .willReturn(ok().withBodyFile("ec2Responses/DescribeKeyPairs.xml"))
        );
        stubFor(
            post(urlEqualTo("/ec2/"))
                .withRequestBody(containing("Action=DescribeInstances"))
                .withRequestBody(matching(".*(?:Filter|InstanceId)\\.\\d+=.*"))
                .willReturn(ok().withBodyFile("ec2Responses/DescribeInstances.xml"))
        );
        stubFor(
            post(urlEqualTo("/ec2/"))
                .withRequestBody(containing("Action=DescribeInstances"))
                .withRequestBody(notMatching(".*(?:Filter|InstanceId)\\.\\d+=.*"))
                .willReturn(ok().withBodyFile("ec2Responses/DescribeInstancesEmpty.xml"))
        );
        stubFor(
            post(urlEqualTo("/ec2/"))
                .withRequestBody(containing("Action=RunInstances"))
                .willReturn(ok().withBodyFile("ec2Responses/RunInstances.xml"))
        );
    }

    @After
    public void tearDown() throws Exception {
        AmazonEC2Cloud.setTestMode(false);
        AmazonEC2Cloud.setEc2TestEndpointUrl(null);
    }

    @Test
    public void testConfigRoundtrip() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, false, "", false, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy");
    }

    @Test
    public void testConfigRoundtripWithCustomConnectionStrategy() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default",
            "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description,
            "bar", "bbb", "aaa", "10", "fff", null,
            "-Xmx1g", false, "subnet 456", tags, null,
            null, "", true, false,
            false, "", false, "",
            false, false, false, SlaveTemplate.BurstableUnlimitedMode.DEFAULT,
            ConnectionStrategy.PUBLIC_IP, -1);

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy");
    }

    /**
     * Tests to make sure the slave created has been configured properly. Also tests to make sure the spot max bid price
     * has been set properly.
     *
     * @throws Exception
     *             - Exception that can be thrown by the Jenkins test harness
     */
    @Test
    public void testConfigWithSpotBidPrice() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SpotConfiguration spotConfig = new SpotConfiguration(true, ".05", false, "");

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, true, null, "", false, false, "", false, "");
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,spotConfig,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }

    /**
     * Tests to make sure the slave created has been configured properly. Also tests to make sure the spot max bid price
     * has been set properly.
     *
     * @throws Exception - Exception that can be thrown by the Jenkins test harness
     */
    @Test public void testSpotConfigWithoutBidPrice() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SpotConfiguration spotConfig = new SpotConfiguration(false, "", false, "");

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, true, null, "", false, false, "", false, "");
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,spotConfig,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }

    /**
     * Tests to make sure the slave created has been configured properly. Also tests to make sure the spot max bid price
     * has been set properly.
     *
     * @throws Exception - Exception that can be thrown by the Jenkins test harness
     */
    @Test public void testSpotConfigWithFallback() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SpotConfiguration spotConfig = new SpotConfiguration(true, "0.1", true, "");

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, true, null, "", false, false, "", false, "");
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,spotConfig,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,connectionStrategy");
    }

    /**
     * Test to make sure the IAM Role is set properly.
     *
     * @throws Exception
     */
    @Test
    public void testConfigRoundtripIamRole() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, false, null, "iamInstanceProfile", false, false, "", false, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,iamInstanceProfile,connectionStrategy");
    }

    @Test
    public void testNullTimeoutShouldReturnMaxInt() {
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, false, null, false, "");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    @Test
    public void testUpdateAmi() {
        SlaveTemplate st = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, false, "0", false, "");
        assertEquals("ami1", st.getAmi());
        st.setAmi("ami2");
        assertEquals("ami2", st.getAmi());
        st.ami = "ami3";
        assertEquals("ami3", st.getAmi());
    }

    @Test
    public void test0TimeoutShouldReturnMaxInt() {
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, false, "0", false, "");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    @Test
    public void testNegativeTimeoutShouldReturnMaxInt() {
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, false, "-1", false, "");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    @Test
    public void testNonNumericTimeoutShouldReturnMaxInt() {
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, false, "NotANumber", false, "");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    @Test
    public void testAssociatePublicIpSetting() {
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, false, null, true, "");
        assertEquals(true, st.getAssociatePublicIp());
    }

    @Test
    public void testConnectUsingPublicIpSetting() {
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, false, false, null, true, "", false, true);
        assertEquals(st.connectionStrategy, ConnectionStrategy.PUBLIC_IP);
    }

    @Test
    public void testConnectUsingPublicIpSettingWithDefaultSetting() {
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, false, null, true, "");
        assertEquals(st.connectionStrategy, ConnectionStrategy.PUBLIC_IP);
    }

    @Test
    public void testBackwardCompatibleUnixData() {
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", "22", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "", "bar", "bbb", "aaa", "10", "rrr", "sudo", null, null, "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, "NotANumber");
        assertFalse(st.isWindowsSlave());
        assertEquals(22, st.getSshPort());
        assertEquals("sudo", st.getRootCommandPrefix());
    }

    @Test
    public void testWindowsConfigRoundTrip() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "rrr", new WindowsData("password", false, ""), "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, false, "", false, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        assertEquals(orig.getAdminPassword(), received.getAdminPassword());
        assertEquals(orig.amiType, received.amiType);
        r.assertEqualBeans(orig, received, "amiType");
    }

    @Test
    public void testUnixConfigRoundTrip() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "rrr", new UnixData("sudo", null, null, "22"), "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, false, "", false, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "amiType");
    }

    @Test
    public void testChooseSubnetId() throws Exception {
        SlaveTemplate slaveTemplate = new SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "AMI description", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet-123 subnet-456", null, null, true, null, "", false, false, "", false, "");

        String subnet1 = slaveTemplate.chooseSubnetId();
        String subnet2 = slaveTemplate.chooseSubnetId();
        String subnet3 = slaveTemplate.chooseSubnetId();

        assertEquals(subnet1, "subnet-123");
        assertEquals(subnet2, "subnet-456");
        assertEquals(subnet3, "subnet-123");
    }

    @Test
    public void testBackwardCompatibleT2UnlimitedDisabled() {
        // If burstableUnlimitedMode is null, then we fallback to the value of t2Unlimited:
        SlaveTemplate.BurstableUnlimitedMode burstableUnlimitedMode = null;
        boolean t2Unlimited = false;

        SlaveTemplate slaveTemplate = new SlaveTemplate("ami", EC2AbstractSlave.TEST_ZONE, null,
            "default", "foo", InstanceType.M1Large, false, "ttt",
            Node.Mode.NORMAL, "description", "bar", "bbb", "aaa",
            "10", "fff", null, "-Xmx1g", false,
            "subnet 456", null, null, null, "",
            true, false, false, "",
            false, "", false, false,
            t2Unlimited, burstableUnlimitedMode, ConnectionStrategy.PUBLIC_IP, -1);

        // t2Unlimited==false means that we don't specify a preference for Unlimited Mode when talking to AWS, i. e. we
        // let AWS choose a suitable value depending on the instance type:
        assertEquals(slaveTemplate.getBurstableUnlimitedMode(), SlaveTemplate.BurstableUnlimitedMode.DEFAULT);
    }

    @Test
    public void testBackwardCompatibleT2UnlimitedEnabled() {
        // If burstableUnlimitedMode is null, then we fallback to the value of t2Unlimited:
        SlaveTemplate.BurstableUnlimitedMode burstableUnlimitedMode = null;
        boolean t2Unlimited = true;

        SlaveTemplate slaveTemplate = new SlaveTemplate("ami", EC2AbstractSlave.TEST_ZONE, null,
            "default", "foo", InstanceType.M1Large, false, "ttt",
            Node.Mode.NORMAL, "description", "bar", "bbb", "aaa",
            "10", "fff", null, "-Xmx1g", false,
            "subnet 456", null, null, null, "",
            true, false, false, "",
            false, "", false, false,
            t2Unlimited, burstableUnlimitedMode, ConnectionStrategy.PUBLIC_IP, -1);

        // t2Unlimited==true means that we explicitly enable Unlimited Mode:
        assertEquals(slaveTemplate.getBurstableUnlimitedMode(), SlaveTemplate.BurstableUnlimitedMode.ENABLED);
    }

    @Test
    public void testBurstableUnlimitedModeLegacyOverride() {
        // If burstableUnlimitedMode is NOT null, then we ignore the value of the legacy t2Unlimited parameter:
        for (SlaveTemplate.BurstableUnlimitedMode burstableUnlimitedMode
                : SlaveTemplate.BurstableUnlimitedMode.class.getEnumConstants()) {
            boolean t2Unlimited = true;

            SlaveTemplate slaveTemplate = new SlaveTemplate("ami", EC2AbstractSlave.TEST_ZONE, null,
                "default", "foo", InstanceType.M1Large, false, "ttt",
                Node.Mode.NORMAL, "description", "bar", "bbb", "aaa",
                "10", "fff", null, "-Xmx1g", false,
                "subnet 456", null, null, null, "",
                true, false, false, "",
                false, "", false, false,
                t2Unlimited, burstableUnlimitedMode, ConnectionStrategy.PUBLIC_IP, -1);

            assertEquals(slaveTemplate.getBurstableUnlimitedMode(), burstableUnlimitedMode);
        }
    }

    /**
     * Verifies that when creating an EC2 instance with BurstableUnlimitedMode.DEFAULT, no CreditSpecification parameter
     * is included in the request that is made to AWS.
     */
    @Test
    public void testBurstableUnlimitedModeDefault()
            throws IOException, CredentialStoreNotFoundException {
        SlaveTemplate.BurstableUnlimitedMode burstableUnlimitedMode = SlaveTemplate.BurstableUnlimitedMode.DEFAULT;

        SlaveTemplate slaveTemplate = new SlaveTemplate("ami", null, null,
            "default", "foo", InstanceType.M1Large, false, "ttt",
            Node.Mode.NORMAL, "description", "bar", "bbb", "aaa",
            "10", "fff", null, "-Xmx1g", false,
            null, null, null, null, "",
            true, false, false, "",
            false, "", false, false,
            false, burstableUnlimitedMode, ConnectionStrategy.PUBLIC_IP, -1);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(slaveTemplate);

        CredentialUtils.addGlobalSystemCredentials(
            new AWSCredentialsImpl(CredentialsScope.SYSTEM, "abc", "foo", "bar", null)
        );
        AmazonEC2Cloud ac = new AmazonEC2Cloud("test-cloud", false,
            "abc", "dummy-region-1", TEST_PRIVATE_SSH_KEY, "3", templates,
            null, null);
        r.jenkins.clouds.add(ac);

        slaveTemplate.provision(1, EnumSet.of(SlaveTemplate.ProvisionOptions.ALLOW_CREATE));

        verify(
            postRequestedFor(urlEqualTo("/ec2/"))
                .withRequestBody(containing("Action=RunInstances"))
                .withRequestBody(notMatching(".*CreditSpecification.*"))
        );
    }

    /**
     * Verifies that when creating an EC2 instance with BurstableUnlimitedMode.ENABLED, the
     * CreditSpecification.CPUCredits parameter in the request that is made to AWS is set to "unlimited".
     */
    @Test
    public void testBurstableUnlimitedModeEnabled()
        throws IOException, CredentialStoreNotFoundException {
        SlaveTemplate.BurstableUnlimitedMode burstableUnlimitedMode = SlaveTemplate.BurstableUnlimitedMode.ENABLED;

        SlaveTemplate slaveTemplate = new SlaveTemplate("ami", null, null,
            "default", "foo", InstanceType.M1Large, false, "ttt",
            Node.Mode.NORMAL, "description", "bar", "bbb", "aaa",
            "10", "fff", null, "-Xmx1g", false,
            null, null, null, null, "",
            true, false, false, "",
            false, "", false, false,
            false, burstableUnlimitedMode, ConnectionStrategy.PUBLIC_IP, -1);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(slaveTemplate);

        CredentialUtils.addGlobalSystemCredentials(
                new AWSCredentialsImpl(CredentialsScope.SYSTEM, "abc", "foo", "bar", null)
        );
        AmazonEC2Cloud ac = new AmazonEC2Cloud("test-cloud", false,
            "abc", "dummy-region-1", TEST_PRIVATE_SSH_KEY, "3", templates,
            null, null);
        r.jenkins.clouds.add(ac);

        slaveTemplate.provision(1, EnumSet.of(SlaveTemplate.ProvisionOptions.ALLOW_CREATE));

        verify(
            postRequestedFor(urlEqualTo("/ec2/"))
                .withRequestBody(containing("Action=RunInstances"))
                .withRequestBody(containing("CreditSpecification.CpuCredits=unlimited"))
        );
    }

    /**
     * Verifies that when creating an EC2 instance with BurstableUnlimitedMode.DISABLED, the
     * CreditSpecification.CPUCredits parameter in the request that is made to AWS is set to "standard".
     */
    @Test
    public void testBurstableUnlimitedModeDisabled()
        throws IOException, CredentialStoreNotFoundException {
        SlaveTemplate.BurstableUnlimitedMode burstableUnlimitedMode = SlaveTemplate.BurstableUnlimitedMode.DISABLED;

        SlaveTemplate slaveTemplate = new SlaveTemplate("ami", null, null,
            "default", "foo", InstanceType.M1Large, false, "ttt",
            Node.Mode.NORMAL, "description", "bar", "bbb", "aaa",
            "10", "fff", null, "-Xmx1g", false,
            null, null, null, null, "",
            true, false, false, "",
            false, "", false, false,
            false, burstableUnlimitedMode, ConnectionStrategy.PUBLIC_IP, -1);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(slaveTemplate);

        CredentialUtils.addGlobalSystemCredentials(
                new AWSCredentialsImpl(CredentialsScope.SYSTEM, "abc", "foo", "bar", null)
        );
        AmazonEC2Cloud ac = new AmazonEC2Cloud("test-cloud", false,
            "abc", "dummy-region-1", TEST_PRIVATE_SSH_KEY, "3", templates,
            null, null);
        r.jenkins.clouds.add(ac);

        slaveTemplate.provision(1, EnumSet.of(SlaveTemplate.ProvisionOptions.ALLOW_CREATE));

        verify(
            postRequestedFor(urlEqualTo("/ec2/"))
                .withRequestBody(containing("Action=RunInstances"))
                .withRequestBody(containing("CreditSpecification.CpuCredits=standard"))
        );
    }
}
