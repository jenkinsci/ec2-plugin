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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.Util;
import hudson.model.Node;
import hudson.plugins.ec2.SlaveTemplate.ProvisionOptions;
import hudson.plugins.ec2.util.KeyPair;
import hudson.plugins.ec2.util.MinimumNumberOfInstancesTimeRangeConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.htmlunit.html.HtmlForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.ArgumentCaptor;
import org.xml.sax.SAXException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.EnclaveOptionsRequest;
import software.amazon.awssdk.services.ec2.model.HttpTokensState;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfile;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataEndpointState;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;
import software.amazon.awssdk.services.ec2.model.RequestSpotInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.Subnet;

/**
 * Basic test to validate SlaveTemplate.
 */
@WithJenkins
class SlaveTemplateTest {
    private final String TEST_AMI = "ami-123";
    private final String TEST_ZONE = EC2AbstractSlave.TEST_ZONE;
    private final SpotConfiguration TEST_SPOT_CFG = null;
    private final String TEST_SEC_GROUPS = "default";
    private final String TEST_REMOTE_FS = "foo";
    private final InstanceType TEST_INSTANCE_TYPE = InstanceType.M1_LARGE;
    private final boolean TEST_EBSO = false;
    private final String TEST_LABEL = "ttt";

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void testConfigRoundtrip() throws Exception {
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<>();
        tags.add(tag1);
        tags.add(tag2);
        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                tags,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                true,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(getConfigForm(ac));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(
                orig,
                received,
                "ami,zone,description,remoteFS,type,javaPath,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,iamInstanceProfile,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy,tenancy,ebsEncryptRootVolume");
        // For already existing strategies, the default is this one
        assertEquals(HostKeyVerificationStrategyEnum.CHECK_NEW_SOFT, received.getHostKeyVerificationStrategy());
    }

    @Test
    void testConfigRoundtripWithCustomSSHHostKeyVerificationStrategy() throws Exception {
        String description = "foo ami";

        // We check this one is set
        final HostKeyVerificationStrategyEnum STRATEGY_TO_CHECK = HostKeyVerificationStrategyEnum.OFF;

        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                null,
                STRATEGY_TO_CHECK,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(getConfigForm(ac));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(
                orig,
                received,
                "ami,zone,description,remoteFS,type,javaPath,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,useDedicatedTenancy,connectionStrategy,hostKeyVerificationStrategy");
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
    void testConfigWithSpotBidPrice() throws Exception {
        String description = "foo ami";

        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice(".05");
        spotConfig.setFallbackToOndemand(true);
        spotConfig.setSpotBlockReservationDuration(0);

        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                spotConfig,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "foo ami",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(getConfigForm(ac));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(
                orig,
                received,
                "ami,zone,spotConfig,description,remoteFS,type,javaPath,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }

    /**
     * Tests to make sure the agent created has been configured properly. Also tests to make sure the spot max bid price
     * has been set properly.
     *
     * @throws Exception - Exception that can be thrown by the Jenkins test harness
     */
    @Test
    void testSpotConfigWithoutBidPrice() throws Exception {
        String description = "foo ami";

        SpotConfiguration spotConfig = new SpotConfiguration(false);

        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                spotConfig,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "foo ami",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(getConfigForm(ac));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(
                orig,
                received,
                "ami,zone,spotConfig,description,remoteFS,type,javaPath,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }

    @Test
    void testWindowsConfigRoundTrip() throws Exception {
        String description = "foo ami";

        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                null,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "rrr",
                new WindowsData("password", false, "", false, true),
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                true,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(getConfigForm(ac));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        assertEquals(orig.getAdminPassword(), received.getAdminPassword());
        assertEquals(orig.amiType, received.amiType);
        r.assertEqualBeans(orig, received, "amiType");
    }

    @Test
    void testUnixConfigRoundTrip() throws Exception {
        String description = "foo ami";

        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "rrr",
                new UnixData("sudo", "", "", "22", ""),
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                true,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(getConfigForm(ac));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "amiType");
    }

    @Test
    void testMinimumNumberOfInstancesActiveRangeConfig() throws Exception {
        MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig =
                new MinimumNumberOfInstancesTimeRangeConfig();
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeFrom("11:00");
        minimumNumberOfInstancesTimeRangeConfig.setMinimumNoInstancesActiveTimeRangeTo("15:00");
        minimumNumberOfInstancesTimeRangeConfig.setMonday(false);
        minimumNumberOfInstancesTimeRangeConfig.setTuesday(true);
        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice("22");
        spotConfig.setFallbackToOndemand(true);
        spotConfig.setSpotBlockReservationDuration(1);
        SlaveTemplate slaveTemplate = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                spotConfig,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "foo ami",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                2,
                0,
                null,
                null,
                true,
                true,
                "",
                false,
                "",
                false,
                false,
                true,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        slaveTemplate.setMinimumNumberOfInstancesTimeRangeConfig(minimumNumberOfInstancesTimeRangeConfig);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(slaveTemplate);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.configRoundtrip();

        MinimumNumberOfInstancesTimeRangeConfig stored =
                r.jenkins.clouds.get(EC2Cloud.class).getTemplates().get(0).getMinimumNumberOfInstancesTimeRangeConfig();
        assertNotNull(stored);
        assertEquals("11:00", stored.getMinimumNoInstancesActiveTimeRangeFrom());
        assertEquals("15:00", stored.getMinimumNoInstancesActiveTimeRangeTo());
        assertFalse(stored.getDay("monday"));
        assertTrue(stored.getDay("tuesday"));
    }

    @Test
    void provisionOndemandSetsAwsNetworkingOnEc2Request() throws Exception {
        boolean associatePublicIp = false;
        String description = "foo ami";
        String subnetId = "some-subnet";
        String securityGroups = "some security group";
        String iamInstanceProfile = "some instance profile";

        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                securityGroups,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                subnetId,
                null,
                null,
                0,
                0,
                null,
                iamInstanceProfile,
                false,
                true,
                "",
                associatePublicIp,
                "",
                false,
                false,
                false,
                ConnectionStrategy.backwardsCompatible(false, false, associatePublicIp),
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        SlaveTemplate noSubnet = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                securityGroups,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "",
                null,
                null,
                0,
                0,
                null,
                iamInstanceProfile,
                false,
                true,
                "",
                associatePublicIp,
                "",
                false,
                false,
                false,
                ConnectionStrategy.backwardsCompatible(false, false, associatePublicIp),
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);
        templates.add(noSubnet);
        for (SlaveTemplate template : templates) {
            Ec2Client mockedEC2 = setupTestForProvisioning(template);

            ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

            template.provision(2, EnumSet.noneOf(ProvisionOptions.class));
            verify(mockedEC2).runInstances(riRequestCaptor.capture());

            RunInstancesRequest actualRequest = riRequestCaptor.getValue();
            List<InstanceNetworkInterfaceSpecification> actualNets = actualRequest.networkInterfaces();

            assertEquals(1, actualNets.size());
            String templateSubnet = Util.fixEmpty(template.getSubnetId());
            assertEquals(actualRequest.subnetId(), templateSubnet);
            if (templateSubnet != null) {
                assertEquals(
                        actualRequest.securityGroupIds(),
                        Stream.of("some-group-id").collect(Collectors.toList()));
            } else {
                assertEquals(
                        actualRequest.securityGroups(),
                        Stream.of(securityGroups).collect(Collectors.toList()));
            }
        }
    }

    @Test
    void provisionOndemandSetsAwsNetworkingOnNetworkInterface() throws Exception {
        boolean associatePublicIp = true;
        String description = "foo ami";
        String subnetId = "some-subnet";
        String securityGroups = "some security group";
        String iamInstanceProfile = "some instance profile";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                securityGroups,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                subnetId,
                tags,
                null,
                0,
                0,
                null,
                iamInstanceProfile,
                false,
                true,
                "",
                associatePublicIp,
                "",
                false,
                false,
                false,
                ConnectionStrategy.backwardsCompatible(false, false, associatePublicIp),
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        SlaveTemplate noSubnet = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                securityGroups,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "",
                tags,
                null,
                0,
                0,
                null,
                iamInstanceProfile,
                false,
                true,
                "",
                associatePublicIp,
                "",
                false,
                false,
                false,
                ConnectionStrategy.backwardsCompatible(false, false, associatePublicIp),
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);
        templates.add(noSubnet);
        for (SlaveTemplate template : templates) {
            Ec2Client mockedEC2 = setupTestForProvisioning(template);

            ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

            template.provision(2, EnumSet.noneOf(ProvisionOptions.class));
            verify(mockedEC2).runInstances(riRequestCaptor.capture());

            RunInstancesRequest actualRequest = riRequestCaptor.getValue();
            InstanceNetworkInterfaceSpecification actualNet =
                    actualRequest.networkInterfaces().get(0);

            assertEquals(actualNet.subnetId(), Util.fixEmpty(template.getSubnetId()));
            assertEquals(actualNet.groups(), Stream.of("some-group-id").collect(Collectors.toList()));
            assertNull(actualRequest.subnetId());
            assertEquals(actualRequest.securityGroupIds(), Collections.emptyList());
            assertEquals(actualRequest.securityGroups(), Collections.emptyList());
        }
    }

    @Issue("JENKINS-64571")
    @Test
    void provisionSpotFallsBackToOndemandWhenSpotQuotaExceeded() throws Exception {
        boolean associatePublicIp = true;
        String description = "foo ami";
        String subnetId = "some-subnet";
        String securityGroups = "some security group";
        String iamInstanceProfile = "some instance profile";

        SpotConfiguration spotConfig = new SpotConfiguration(true);
        spotConfig.setSpotMaxBidPrice(".05");
        spotConfig.setFallbackToOndemand(true);
        spotConfig.setSpotBlockReservationDuration(0);

        SlaveTemplate template = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                spotConfig,
                securityGroups,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                subnetId,
                null,
                null,
                0,
                0,
                null,
                iamInstanceProfile,
                false,
                true,
                "",
                associatePublicIp,
                "",
                false,
                false,
                false,
                ConnectionStrategy.backwardsCompatible(false, false, associatePublicIp),
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);

        Ec2Client mockedEC2 = setupTestForProvisioning(template);

        AwsServiceException quotaExceededException = Ec2Exception.builder()
                .statusCode(400)
                .requestId("00000000-0000-0000-0000-000000000000")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .serviceName("AmazonEC2")
                        .errorCode("MaxSpotInstanceCountExceeded")
                        .build())
                .build();

        when(mockedEC2.requestSpotInstances(any(RequestSpotInstancesRequest.class)))
                .thenThrow(quotaExceededException);

        template.provision(2, EnumSet.of(ProvisionOptions.ALLOW_CREATE));

        verify(mockedEC2).runInstances(any(RunInstancesRequest.class));
    }

    private Ec2Client setupTestForProvisioning(SlaveTemplate template) throws Exception {
        EC2Cloud mockedCloud = mock(EC2Cloud.class);
        Ec2Client mockedEC2 = mock(Ec2Client.class);
        EC2PrivateKey mockedPrivateKey = mock(EC2PrivateKey.class);
        KeyPair mockedKeyPair =
                new KeyPair(KeyPairInfo.builder().keyName("some-key-name").build(), "some-material");
        when(mockedPrivateKey.find(mockedEC2)).thenReturn(mockedKeyPair);
        when(mockedCloud.connect()).thenReturn(mockedEC2);
        when(mockedCloud.resolvePrivateKey()).thenReturn(mockedPrivateKey);

        template.parent = mockedCloud;

        DescribeImagesResponse mockedImagesResult = mock(DescribeImagesResponse.class);
        Image mockedImage = Image.builder().imageId(template.getAmi()).build();
        when(mockedImagesResult.images()).thenReturn(Stream.of(mockedImage).collect(Collectors.toList()));
        when(mockedEC2.describeImages(any(DescribeImagesRequest.class))).thenReturn(mockedImagesResult);

        DescribeSecurityGroupsResponse mockedSecurityGroupsResult = mock(DescribeSecurityGroupsResponse.class);
        SecurityGroup mockedSecurityGroup = SecurityGroup.builder()
                .vpcId("some-vpc-id")
                .groupId("some-group-id")
                .build();

        List<SecurityGroup> mockedSecurityGroups =
                Stream.of(mockedSecurityGroup).collect(Collectors.toList());
        when(mockedSecurityGroupsResult.securityGroups()).thenReturn(mockedSecurityGroups);
        when(mockedEC2.describeSecurityGroups(any(DescribeSecurityGroupsRequest.class)))
                .thenReturn(mockedSecurityGroupsResult);

        DescribeSubnetsResponse mockedDescribeSubnetsResult = mock(DescribeSubnetsResponse.class);
        Subnet mockedSubnet = Subnet.builder().build();
        List<Subnet> mockedSubnets = Stream.of(mockedSubnet).collect(Collectors.toList());
        when(mockedDescribeSubnetsResult.subnets()).thenReturn(mockedSubnets);
        when(mockedEC2.describeSubnets(any(DescribeSubnetsRequest.class))).thenReturn(mockedDescribeSubnetsResult);

        IamInstanceProfile.Builder mockedInstanceProfileBuilder = IamInstanceProfile.builder();
        mockedInstanceProfileBuilder.arn(template.getIamInstanceProfile());
        Instance mockedInstance = Instance.builder()
                .state(software.amazon.awssdk.services.ec2.model.InstanceState.builder()
                        .name(InstanceStateName.RUNNING)
                        .build())
                .iamInstanceProfile(mockedInstanceProfileBuilder.build())
                .build();
        Reservation mockedReservation = Reservation.builder()
                .instances(Stream.of(mockedInstance).collect(Collectors.toList()))
                .build();
        List<Reservation> mockedReservations = Stream.of(mockedReservation).collect(Collectors.toList());
        DescribeInstancesResponse mockedDescribedInstancesResult = mock(DescribeInstancesResponse.class);
        when(mockedDescribedInstancesResult.reservations()).thenReturn(mockedReservations);
        when(mockedEC2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(mockedDescribedInstancesResult);

        RunInstancesResponse mockedResult = mock(RunInstancesResponse.class);
        when(mockedResult.reservationId()).thenReturn(mockedReservation.reservationId());
        when(mockedEC2.runInstances(any(RunInstancesRequest.class))).thenReturn(mockedResult);

        return mockedEC2;
    }

    @Test
    void testMacConfig() throws Exception {
        String description = "foo ami";
        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                "foo",
                InstanceType.MAC1_METAL.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                new MacData("sudo", null, null, "22", null),
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);

        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(getConfigForm(ac));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "type,amiType");
    }

    @Issue("JENKINS-65569")
    @Test
    void testAgentName() {
        SlaveTemplate broken = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "broken/description",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        SlaveTemplate working = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "working",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(broken);
        templates.add(working);
        EC2Cloud brokenCloud =
                new EC2Cloud("broken/cloud", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        assertThat(broken.getSlaveName("test"), is("test"));
        assertThat(working.getSlaveName("test"), is("test"));
        EC2Cloud workingCloud =
                new EC2Cloud("cloud", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        assertThat(broken.getSlaveName("test"), is("test"));
        assertThat(working.getSlaveName("test"), is("EC2 (cloud) - working (test)"));
    }

    @Test
    void testMetadataV2Config() throws Exception {
        final String slaveDescription = "foobar";
        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                slaveDescription,
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "java",
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                true,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                true,
                true,
                2,
                true,
                false);

        List<SlaveTemplate> templates = Collections.singletonList(orig);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(r.createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(slaveDescription);
        r.assertEqualBeans(
                orig,
                received,
                "ami,zone,description,remoteFS,type,javaPath,jvmopts,stopOnTerminate,securityGroups,subnetId,useEphemeralDevices,connectionStrategy,hostKeyVerificationStrategy,metadataEndpointEnabled,metadataTokensRequired,metadataHopsLimit");
    }

    @Test
    void provisionOnDemandWithUnsupportedInstanceMetadata() throws Exception {
        SlaveTemplate template = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "java",
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                true,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                true,
                false,
                2,
                false,
                false);

        Ec2Client mockedEC2 = setupTestForProvisioning(template);

        ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

        template.provision(2, EnumSet.noneOf(ProvisionOptions.class));
        verify(mockedEC2).runInstances(riRequestCaptor.capture());

        RunInstancesRequest actualRequest = riRequestCaptor.getValue();
        InstanceMetadataOptionsRequest metadataOptionsRequest = actualRequest.metadataOptions();
        assertNull(metadataOptionsRequest);
    }

    @Test
    void provisionOnDemandSetsMetadataV1Options() throws Exception {
        SlaveTemplate template = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "java",
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                true,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                true,
                false,
                2,
                true,
                false);

        Ec2Client mockedEC2 = setupTestForProvisioning(template);

        ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

        template.provision(2, EnumSet.noneOf(ProvisionOptions.class));
        verify(mockedEC2).runInstances(riRequestCaptor.capture());

        RunInstancesRequest actualRequest = riRequestCaptor.getValue();
        InstanceMetadataOptionsRequest metadataOptionsRequest = actualRequest.metadataOptions();
        assertEquals(InstanceMetadataEndpointState.ENABLED, metadataOptionsRequest.httpEndpoint());
        assertEquals(HttpTokensState.OPTIONAL, metadataOptionsRequest.httpTokens());
        assertEquals(metadataOptionsRequest.httpPutResponseHopLimit(), Integer.valueOf(2));
    }

    @Test
    void provisionOnDemandSetsMetadataV2Options() throws Exception {
        SlaveTemplate template = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "java",
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                true,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                true,
                true,
                2,
                true,
                false);

        Ec2Client mockedEC2 = setupTestForProvisioning(template);

        ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

        template.provision(2, EnumSet.noneOf(ProvisionOptions.class));
        verify(mockedEC2).runInstances(riRequestCaptor.capture());

        RunInstancesRequest actualRequest = riRequestCaptor.getValue();
        InstanceMetadataOptionsRequest metadataOptionsRequest = actualRequest.metadataOptions();
        assertEquals(InstanceMetadataEndpointState.ENABLED, metadataOptionsRequest.httpEndpoint());
        assertEquals(HttpTokensState.REQUIRED, metadataOptionsRequest.httpTokens());
        assertEquals(metadataOptionsRequest.httpPutResponseHopLimit(), Integer.valueOf(2));
    }

    @Test
    void provisionOnDemandSetsMetadataDefaultOptions() throws Exception {
        SlaveTemplate template = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "java",
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                true,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                null,
                true,
                null,
                true,
                false);

        Ec2Client mockedEC2 = setupTestForProvisioning(template);

        ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

        template.provision(2, EnumSet.noneOf(ProvisionOptions.class));
        verify(mockedEC2).runInstances(riRequestCaptor.capture());

        RunInstancesRequest actualRequest = riRequestCaptor.getValue();
        InstanceMetadataOptionsRequest metadataOptionsRequest = actualRequest.metadataOptions();
        assertEquals(InstanceMetadataEndpointState.ENABLED, metadataOptionsRequest.httpEndpoint());
        assertEquals(HttpTokensState.REQUIRED, metadataOptionsRequest.httpTokens());
        assertEquals(metadataOptionsRequest.httpPutResponseHopLimit(), Integer.valueOf(1));
    }

    @Test
    void provisionOnDemandSetsMetadataDefaultOptionsWithEC2Exception() throws Exception {
        SlaveTemplate template = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "java",
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                true,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                null,
                true,
                null,
                true,
                false);
        Ec2Client mockedEC2 = setupTestForProvisioning(template);
        when(mockedEC2.runInstances(any(RunInstancesRequest.class)))
                .thenThrow(Ec2Exception.builder()
                        .message("InsufficientInstanceCapacity")
                        .build());
        assertThrows(Ec2Exception.class, () -> template.provision(2, EnumSet.noneOf(ProvisionOptions.class)));
    }

    @Test
    void provisionOnDemandWithEnclaveEnabled() throws Exception {
        SlaveTemplate template = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "java",
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                true,
                false,
                "",
                false,
                "",
                true,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                null,
                true,
                null,
                true,
                true);

        Ec2Client mockedEC2 = setupTestForProvisioning(template);

        ArgumentCaptor<RunInstancesRequest> riRequestCaptor = ArgumentCaptor.forClass(RunInstancesRequest.class);

        template.provision(2, EnumSet.noneOf(ProvisionOptions.class));
        verify(mockedEC2).runInstances(riRequestCaptor.capture());

        RunInstancesRequest actualRequest = riRequestCaptor.getValue();
        EnclaveOptionsRequest enclaveOptionsRequest = actualRequest.enclaveOptions();
        assertEquals(Boolean.TRUE, enclaveOptionsRequest.enabled());
    }

    @Test
    public void testWindowsSSHConfigRoundTrip() throws Exception {
        String description = "foo ami";

        SlaveTemplate orig = new SlaveTemplate(
                TEST_AMI,
                TEST_ZONE,
                TEST_SPOT_CFG,
                TEST_SEC_GROUPS,
                TEST_REMOTE_FS,
                TEST_INSTANCE_TYPE.toString(),
                TEST_EBSO,
                TEST_LABEL,
                Node.Mode.NORMAL,
                description,
                "bar",
                "bbb",
                "aaa",
                "10",
                "rrr",
                new WindowsSSHData("CMD /C", "", "", "22", ""),
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                true,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(orig);

        EC2Cloud ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
        r.jenkins.clouds.add(ac);

        r.submit(getConfigForm(ac));
        SlaveTemplate received = ((EC2Cloud) r.jenkins.clouds.iterator().next()).getTemplate(description);
        r.assertEqualBeans(orig, received, "amiType");
    }

    private HtmlForm getConfigForm(EC2Cloud ac) throws IOException, SAXException {
        return r.createWebClient().goTo(ac.getUrl() + "configure").getFormByName("config");
    }

     @Test
    void testNetworkInterfaceAssociatePublicIpTrue() throws Exception {
        SlaveTemplate template = mock(SlaveTemplate.class);
        when(template.getAssociatePublicIp()).thenReturn(true);
        InstanceNetworkInterfaceSpecification.Builder netBuilder = InstanceNetworkInterfaceSpecification.builder();
        netBuilder.associatePublicIpAddress(true);
        netBuilder.deviceIndex(0);
        RunInstancesRequest.Builder riRequestBuilder = RunInstancesRequest.builder();
        riRequestBuilder.networkInterfaces(netBuilder.build());
        RunInstancesRequest req = riRequestBuilder.build();
        List<InstanceNetworkInterfaceSpecification> interfaces = req.networkInterfaces();
        assertEquals(1, interfaces.size());
        assertTrue(interfaces.get(0).associatePublicIpAddress());
    }

    @Test
    void testNetworkInterfaceAssociatePublicIpFalse() throws Exception {
        SlaveTemplate template = mock(SlaveTemplate.class);
        when(template.getAssociatePublicIp()).thenReturn(false);
        InstanceNetworkInterfaceSpecification.Builder netBuilder = InstanceNetworkInterfaceSpecification.builder();
        netBuilder.associatePublicIpAddress(false);
        netBuilder.deviceIndex(0);
        RunInstancesRequest.Builder riRequestBuilder = RunInstancesRequest.builder();
        riRequestBuilder.networkInterfaces(netBuilder.build());
        RunInstancesRequest req = riRequestBuilder.build();
        List<InstanceNetworkInterfaceSpecification> interfaces = req.networkInterfaces();
        assertEquals(1, interfaces.size());
        assertFalse(interfaces.get(0).associatePublicIpAddress());
    }
}
