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
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeImagesResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.DescribeSubnetsRequest;
import com.amazonaws.services.ec2.model.DescribeSubnetsResult;
import com.amazonaws.services.ec2.model.IamInstanceProfile;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceNetworkInterfaceSpecification;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.identitymanagement.model.InstanceProfile;

import hudson.model.Node;
import hudson.plugins.ec2.SlaveTemplate.ProvisionOptions;
import hudson.plugins.ec2.util.MinimumNumberOfInstancesTimeRangeConfig;
import com.amazonaws.services.ec2.model.Reservation;
import jenkins.model.Jenkins;

import net.sf.json.JSONObject;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Matchers.any;

/**
 * Basic test to validate SlaveTemplate.
 */
public class SlaveTemplateTest {

    @Rule public JenkinsRule r = new JenkinsRule();

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
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy");
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

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, null, "", true, false, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1);

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy");
    }

    @Test
    public void testDefaultSSHHostKeyVerificationStrategy() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        List<EC2Tag> tags = new ArrayList<EC2Tag>();

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, 0, 0, null, "", true, false, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null);

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy");
        // For already existing strategies, the default is this one
        assertEquals(HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT, received.getHostKeyVerificationStrategy());
    }

    @Test
    public void testConfigRoundtripWithCustomSSHHostKeyVerificationStrategy() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        // We check this one is set
        final HostKeyVerificationStrategyEnum STRATEGY_TO_CHECK = HostKeyVerificationStrategyEnum.OFF;
        
        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, 0, 0, null, "", true, false, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null, STRATEGY_TO_CHECK);

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy");
        assertEquals(STRATEGY_TO_CHECK, received.getHostKeyVerificationStrategy());
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

        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice(".05");
        spotConfig.setFallbackToOndemand(false);
        spotConfig.setSpotBlockReservationDuration(0);

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
    @Test
    public void testSpotConfigWithoutBidPrice() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SpotConfiguration spotConfig = new SpotConfiguration(false);

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
    @Test
    public void testSpotConfigWithFallback() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice("0.1");
        spotConfig.setFallbackToOndemand(true);
        spotConfig.setSpotBlockReservationDuration(0);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, true, null, "", false, false, "", false, "");
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,spotConfig,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,connectionStrategy,hostKeyVerificationStrategy");
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
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,iamInstanceProfile,connectionStrategy,hostKeyVerificationStrategy");
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

    @Issue("JENKINS-59460")
    @Test
    public void testConnectionStrategyDeprecatedFieldsAreExported() {
        SlaveTemplate template = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", Collections.singletonList(new EC2Tag("name1", "value1")), null, false, null, "", true, false, "", false, "");

        String exported = Jenkins.XSTREAM.toXML(template);
        assertThat(exported, containsString("usePrivateDnsName"));
        assertThat(exported, containsString("connectUsingPublicIp"));
    }

    @Test
    public void testMinimumNumberOfInstancesActiveRangeConfig() throws Exception {
        MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig = new MinimumNumberOfInstancesTimeRangeConfig();
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeFrom("11:00");
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeTo("15:00");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("monday", false);
        jsonObject.put("tuesday", true);
        jsonObject.put("wednesday", false);
        jsonObject.put("thursday", false);
        jsonObject.put("friday", false);
        jsonObject.put("saturday", false);
        jsonObject.put("sunday", false);
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeDays(jsonObject);
        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice("22");
        spotConfig.setFallbackToOndemand(true);
        spotConfig.setSpotBlockReservationDuration(1);
        SlaveTemplate slaveTemplate = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, 2, null, null, true, true, false, "", false, "", false, false, true, ConnectionStrategy.PRIVATE_IP, 0);
        slaveTemplate.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(slaveTemplate);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.configRoundtrip();

        MinimumNumberOfInstancesTimeRangeConfig stored = r.jenkins.clouds.get(AmazonEC2Cloud.class).getTemplates().get(0).getMinimumNumberOfInstancesTimeRangeConfig();
        Assert.assertNotNull(stored);
        Assert.assertEquals("11:00", stored.getMinimumNoInstancesActiveTimeRangeFrom());
        Assert.assertEquals("15:00", stored.getMinimumNoInstancesActiveTimeRangeTo());
        Assert.assertEquals(false, stored.getMinimumNoInstancesActiveTimeRangeDays().get("monday"));
        Assert.assertEquals(true, stored.getMinimumNoInstancesActiveTimeRangeDays().get("tuesday"));
    }

  @Test
  public void provisionOndemandSetsAwsNetworkingOnEc2Request() throws Exception {
        boolean associatePublicIp = false;
        String ami = "ami1";
        String description = "foo ami";
        String subnetId = "some-subnet";
        String securityGroups = "some security group";
        String iamInstanceProfile = "some instance profile";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, securityGroups, "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, subnetId, tags, null, false, null, iamInstanceProfile, true, false, "", associatePublicIp, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);
        AmazonEC2 mockedEC2 = setupTestForProvisioning(orig);

        ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

        orig.provision(2, EnumSet.noneOf(ProvisionOptions.class));
        verify(mockedEC2).runInstances(riRequestCaptor.capture());

        RunInstancesRequest actualRequest = riRequestCaptor.getValue();
        List<InstanceNetworkInterfaceSpecification> actualNets = actualRequest.getNetworkInterfaces();

        assertEquals(actualNets.size(), 0);
        assertEquals(actualRequest.getSubnetId(), subnetId);
        assertEquals(actualRequest.getSecurityGroupIds(), Stream.of("some-group-id").collect(Collectors.toList()));
  }

  @Test
  public void provisionOndemandSetsAwsNetworkingOnNetworkInterface() throws Exception {
        boolean associatePublicIp = true;
        String ami = "ami1";
        String description = "foo ami";
        String subnetId = "some-subnet";
        String securityGroups = "some security group";
        String iamInstanceProfile = "some instance profile";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, securityGroups, "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, subnetId, tags, null, false, null, iamInstanceProfile, true, false, "", associatePublicIp, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);
        AmazonEC2 mockedEC2 = setupTestForProvisioning(orig);

        ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

        orig.provision(2, EnumSet.noneOf(ProvisionOptions.class));
        verify(mockedEC2).runInstances(riRequestCaptor.capture());

        RunInstancesRequest actualRequest = riRequestCaptor.getValue();
        InstanceNetworkInterfaceSpecification actualNet = actualRequest.getNetworkInterfaces().get(0);

        assertEquals(actualNet.getSubnetId(), subnetId);
        assertEquals(actualNet.getGroups(), Stream.of("some-group-id").collect(Collectors.toList()));
        assertEquals(actualRequest.getSubnetId(), null);
        assertEquals(actualRequest.getSecurityGroupIds(), Collections.emptyList());
  }

  private AmazonEC2 setupTestForProvisioning(SlaveTemplate template) throws Exception {
        AmazonEC2Cloud mockedCloud = mock(AmazonEC2Cloud.class);
        AmazonEC2 mockedEC2 = mock(AmazonEC2.class);
        EC2PrivateKey mockedPrivateKey = mock(EC2PrivateKey.class);
        KeyPair mockedKeyPair = new KeyPair();
        mockedKeyPair.setKeyName("some-key-name");
        when(mockedPrivateKey.find(mockedEC2)).thenReturn(mockedKeyPair);
        when(mockedCloud.connect()).thenReturn(mockedEC2);
        when(mockedCloud.getPrivateKey()).thenReturn(mockedPrivateKey);
        template.parent = mockedCloud;

        DescribeImagesResult mockedImagesResult = mock(DescribeImagesResult.class);
        Image mockedImage = new Image();
        mockedImage.setImageId(template.getAmi());
        when(mockedImagesResult.getImages()).thenReturn(Stream.of(mockedImage).collect(Collectors.toList()));
        when(mockedEC2.describeImages(any(DescribeImagesRequest.class))).thenReturn(mockedImagesResult);

        DescribeSecurityGroupsResult mockedSecurityGroupsResult = mock(DescribeSecurityGroupsResult.class);
        SecurityGroup mockedSecurityGroup = new SecurityGroup();
        mockedSecurityGroup.setVpcId("some-vpc-id");
        mockedSecurityGroup.setGroupId("some-group-id");

        List<SecurityGroup> mockedSecurityGroups = Stream.of(mockedSecurityGroup).collect(Collectors.toList());
        when(mockedSecurityGroupsResult.getSecurityGroups()).thenReturn(mockedSecurityGroups);
        when(mockedEC2.describeSecurityGroups(any(DescribeSecurityGroupsRequest.class))).thenReturn(mockedSecurityGroupsResult);

        DescribeSubnetsResult mockedDescribeSubnetsResult = mock(DescribeSubnetsResult.class);
        Subnet mockedSubnet = new Subnet();
        List<Subnet> mockedSubnets = Stream.of(mockedSubnet).collect(Collectors.toList());
        when(mockedDescribeSubnetsResult.getSubnets()).thenReturn(mockedSubnets);
        when(mockedEC2.describeSubnets(any(DescribeSubnetsRequest.class))).thenReturn(mockedDescribeSubnetsResult);

        IamInstanceProfile mockedInstanceProfile = new IamInstanceProfile();
        mockedInstanceProfile.setArn(template.getIamInstanceProfile());
        InstanceState mockInstanceState = new InstanceState();
        mockInstanceState.setName("not terminated");
        Instance mockedInstance = new Instance();
        mockedInstance.setState(mockInstanceState);
        mockedInstance.setIamInstanceProfile(mockedInstanceProfile);
        Reservation mockedReservation = new Reservation();
        mockedReservation.setInstances(Stream.of(mockedInstance).collect(Collectors.toList()));
        List<Reservation> mockedReservations = Stream.of(mockedReservation).collect(Collectors.toList());
        DescribeInstancesResult mockedDescribedInstancesResult = mock(DescribeInstancesResult.class);
        when(mockedDescribedInstancesResult.getReservations()).thenReturn(mockedReservations);
        when(mockedEC2.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(mockedDescribedInstancesResult);

        RunInstancesResult mockedResult = mock(RunInstancesResult.class);
        when(mockedResult.getReservation()).thenReturn(mockedReservation);
        when(mockedEC2.runInstances(any(RunInstancesRequest.class))).thenReturn(mockedResult);

        return mockedEC2;
  }
}
