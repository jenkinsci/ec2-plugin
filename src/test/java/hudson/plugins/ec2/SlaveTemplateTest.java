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
import com.amazonaws.services.ec2.model.AmazonEC2Exception;
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
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Subnet;
import hudson.Util;
import hudson.model.Node;
import hudson.plugins.ec2.SlaveTemplate.ProvisionOptions;
import hudson.plugins.ec2.util.MinimumNumberOfInstancesTimeRangeConfig;
import jenkins.model.Jenkins;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Basic test to validate SlaveTemplate.
 */
public class SlaveTemplateTest {

    @Rule public JenkinsRule r = new JenkinsRule();

    @Test
    public void testConfigRoundtrip() throws Exception {
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<>();
        tags.add(tag1);
        tags.add(tag2);
        SlaveTemplate orig = new  SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, 0, 0, null, "iamInstanceProfile", true, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null, null, Tenancy.Default, EbsEncryptRootVolume.DEFAULT);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,iamInstanceProfile,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy,tenancy,ebsEncryptRootVolume");
    }

    @Test
    public void testConfigRoundtripWithCustomConnectionStrategy() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, null, "", true, false, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy");
    }

    @Test
    public void testDefaultSSHHostKeyVerificationStrategy() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        List<EC2Tag> tags = new ArrayList<>();

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, 0, 0, null, "", true, false, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy");
        // For already existing strategies, the default is this one
        assertEquals(HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT, received.getHostKeyVerificationStrategy());
    }

    @Test
    public void testConfigRoundtripWithCustomSSHHostKeyVerificationStrategy() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        // We check this one is set
        final HostKeyVerificationStrategyEnum STRATEGY_TO_CHECK = HostKeyVerificationStrategyEnum.OFF;

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, 0, 0, null, "", true, false, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null, STRATEGY_TO_CHECK);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy");
        assertEquals(STRATEGY_TO_CHECK, received.getHostKeyVerificationStrategy());
    }

    /**
     * Tests to make sure the agent created has been configured properly. Also tests to make sure the spot max bid price
     * has been set properly.
     *
     * @throws Exception
     *             - Exception that can be thrown by the Jenkins test harness
     */
    @Test
    public void testConfigWithSpotBidPrice() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice(".05");
        spotConfig.setFallbackToOndemand(false);
        spotConfig.setSpotBlockReservationDuration(0);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, true, null, "", false, false, "", false, "");
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,spotConfig,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }

    /**
     * Tests to make sure the agent created has been configured properly. Also tests to make sure the spot max bid price
     * has been set properly.
     *
     * @throws Exception - Exception that can be thrown by the Jenkins test harness
     */
    @Test
    public void testSpotConfigWithoutBidPrice() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        SpotConfiguration spotConfig = new SpotConfiguration(false);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, true, null, "", false, false, "", false, "");
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,spotConfig,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }

    /**
     * Tests to make sure the agent created has been configured properly. Also tests to make sure the spot max bid price
     * has been set properly.
     *
     * @throws Exception - Exception that can be thrown by the Jenkins test harness
     */
    @Test
    public void testSpotConfigWithFallback() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice("0.1");
        spotConfig.setFallbackToOndemand(true);
        spotConfig.setSpotBlockReservationDuration(0);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, true, null, "", false, false, "", false, "");
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "ami,zone,spotConfig,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,connectionStrategy,hostKeyVerificationStrategy");
    }

    @Test
    public void testWindowsConfigRoundTrip() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "rrr", new WindowsData("password", false, ""), "-Xmx1g", false, "subnet 456", null, null, false, null, "", true, false, "", false, "");

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        assertEquals(orig.getAdminPassword(), received.getAdminPassword());
        assertEquals(orig.amiType, received.amiType);
        r.assertEqualBeans(orig, received, "amiType");
    }

    @Test
    public void testUnixConfigRoundTrip() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "rrr", new UnixData("sudo", null, null, "22"), "-Xmx1g", false, "subnet 456", null, null, false, null, "", true, false, "", false, "");

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "amiType");
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
        minimumNumberOfInstancesTimeRangeConfig.setMonday(false);
        minimumNumberOfInstancesTimeRangeConfig.setTuesday(true);
        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice("22");
        spotConfig.setFallbackToOndemand(true);
        spotConfig.setSpotBlockReservationDuration(1);
        SlaveTemplate slaveTemplate = new SlaveTemplate("ami1", EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, 2, null, null, true, true, false, "", false, "", false, false, true, ConnectionStrategy.PRIVATE_IP, 0);
        slaveTemplate.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(slaveTemplate);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.configRoundtrip();

        MinimumNumberOfInstancesTimeRangeConfig stored = r.jenkins.clouds.get(AmazonEC2Cloud.class).getTemplates().get(0).getMinimumNumberOfInstancesTimeRangeConfig();
        Assert.assertNotNull(stored);
        Assert.assertEquals("11:00", stored.getMinimumNoInstancesActiveTimeRangeFrom());
        Assert.assertEquals("15:00", stored.getMinimumNoInstancesActiveTimeRangeTo());
        Assert.assertEquals(false, stored.getDay("monday"));
        Assert.assertEquals(true, stored.getDay("tuesday"));
    }

    @Test
    public void provisionOndemandSetsAwsNetworkingOnEc2Request() throws Exception {
        boolean associatePublicIp = false;
        String ami = "ami1";
        String description = "foo ami";
        String subnetId = "some-subnet";
        String securityGroups = "some security group";
        String iamInstanceProfile = "some instance profile";

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, securityGroups, "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, subnetId, null, null, false, null, iamInstanceProfile, true, false, "", associatePublicIp, "");
        SlaveTemplate noSubnet = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, securityGroups, "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "", null, null, false, null, iamInstanceProfile, true, false, "", associatePublicIp, "");

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);
        templates.add(noSubnet);
        for (SlaveTemplate template : templates) {
            AmazonEC2 mockedEC2 = setupTestForProvisioning(template);

            ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

            template.provision(2, EnumSet.noneOf(ProvisionOptions.class));
            verify(mockedEC2).runInstances(riRequestCaptor.capture());

            RunInstancesRequest actualRequest = riRequestCaptor.getValue();
            List<InstanceNetworkInterfaceSpecification> actualNets = actualRequest.getNetworkInterfaces();

            assertEquals(actualNets.size(), 0);
            String templateSubnet = Util.fixEmpty(template.getSubnetId());
            assertEquals(actualRequest.getSubnetId(), templateSubnet);
            if (templateSubnet != null) {
                assertEquals(actualRequest.getSecurityGroupIds(), Stream.of("some-group-id").collect(Collectors.toList()));
            } else {
                assertEquals(actualRequest.getSecurityGroups(), Stream.of(securityGroups).collect(Collectors.toList()));
            }
        }
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
        List<EC2Tag> tags = new ArrayList<>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, securityGroups, "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, subnetId, tags, null, false, null, iamInstanceProfile, true, false, "", associatePublicIp, "");
        SlaveTemplate noSubnet = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, securityGroups, "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "", tags, null, false, null, iamInstanceProfile, true, false, "", associatePublicIp, "");

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);
        templates.add(noSubnet);
        for (SlaveTemplate template : templates) {
            AmazonEC2 mockedEC2 = setupTestForProvisioning(template);

            ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

            template.provision(2, EnumSet.noneOf(ProvisionOptions.class));
            verify(mockedEC2).runInstances(riRequestCaptor.capture());

            RunInstancesRequest actualRequest = riRequestCaptor.getValue();
            InstanceNetworkInterfaceSpecification actualNet = actualRequest.getNetworkInterfaces().get(0);

            assertEquals(actualNet.getSubnetId(), Util.fixEmpty(template.getSubnetId()));
            assertEquals(actualNet.getGroups(), Stream.of("some-group-id").collect(Collectors.toList()));
            assertEquals(actualRequest.getSubnetId(), null);
            assertEquals(actualRequest.getSecurityGroupIds(), Collections.emptyList());
            assertEquals(actualRequest.getSecurityGroups(), Collections.emptyList());
        }
    }

    @Issue("JENKINS-64571")
    @Test
    public void provisionSpotFallsBackToOndemandWhenSpotQuotaExceeded() throws Exception {
        boolean associatePublicIp = true;
        String ami = "ami1";
        String description = "foo ami";
        String subnetId = "some-subnet";
        String securityGroups = "some security group";
        String iamInstanceProfile = "some instance profile";

        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice(".05");
        spotConfig.setFallbackToOndemand(true);
        spotConfig.setSpotBlockReservationDuration(0);

        SlaveTemplate template = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, spotConfig, securityGroups, "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, subnetId, null, null, false, null, iamInstanceProfile, true, false, "", associatePublicIp, "");

        AmazonEC2 mockedEC2 = setupTestForProvisioning(template);

        AmazonEC2Exception quotaExceededException = new AmazonEC2Exception("Request has expired");
        quotaExceededException.setServiceName("AmazonEC2");
        quotaExceededException.setStatusCode(400);
        quotaExceededException.setErrorCode("MaxSpotInstanceCountExceeded");
        quotaExceededException.setRequestId("00000000-0000-0000-0000-000000000000");
        when(mockedEC2.requestSpotInstances(any(RequestSpotInstancesRequest.class))).thenThrow(quotaExceededException);

        template.provision(2, EnumSet.of(ProvisionOptions.ALLOW_CREATE));

        verify(mockedEC2).runInstances(any(RunInstancesRequest.class));
    }

    private AmazonEC2 setupTestForProvisioning(SlaveTemplate template) throws Exception {
        AmazonEC2Cloud mockedCloud = mock(AmazonEC2Cloud.class);
        AmazonEC2 mockedEC2 = mock(AmazonEC2.class);
        EC2PrivateKey mockedPrivateKey = mock(EC2PrivateKey.class);
        KeyPair mockedKeyPair = new KeyPair();
        mockedKeyPair.setKeyName("some-key-name");
        when(mockedPrivateKey.find(mockedEC2)).thenReturn(mockedKeyPair);
        when(mockedCloud.connect()).thenReturn(mockedEC2);
        when(mockedCloud.resolvePrivateKey()).thenReturn(mockedPrivateKey);

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

    @Test
    public void testMacConfig() throws Exception {
        String description = "foo ami";
        SlaveTemplate orig = new  SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.Mac1Metal, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", new MacData("sudo", null, null, "22"), "-Xmx1g", false, "subnet 456", null, null, 0, 0, null, "", true, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null, null, Tenancy.Default);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configureClouds").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "type,amiType");
    }

    @Issue("JENKINS-65569")
    @Test
    public void testAgentName() {
        SlaveTemplate broken = new SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "broken/description", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, 0, 0, null, "", true, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null, null, Tenancy.Default);
        SlaveTemplate working = new SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "working", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, 0, 0, null, "", true, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null, null, Tenancy.Default);
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(broken);
        templates.add(working);
        AmazonEC2Cloud brokenCloud = new AmazonEC2Cloud("broken/cloud", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        assertThat(broken.getSlaveName("test"), is("test"));
        assertThat(working.getSlaveName("test"), is("test"));
        AmazonEC2Cloud workingCloud = new AmazonEC2Cloud("cloud", false, "abc", "us-east-1", "ghi", "3", templates, null, null);
        assertThat(broken.getSlaveName("test"), is("test"));
        assertThat(working.getSlaveName("test"), is("EC2 (cloud) - working (test)"));
    }
}
