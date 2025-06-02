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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * Unit tests related to {@link EC2Cloud}, but do not require a Jenkins instance.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EC2CloudUnitTest {

    @Test
    void testBootstrapRegion() throws Exception {
        assertEquals(Region.US_EAST_1, EC2Cloud.getBootstrapRegion(null));
        assertEquals(Region.US_EAST_1, EC2Cloud.getBootstrapRegion(new URI("")));
        assertEquals(Region.US_EAST_1, EC2Cloud.getBootstrapRegion(new URI("https://ec2.amazonaws.com/")));
        assertEquals(Region.US_EAST_1, EC2Cloud.getBootstrapRegion(new URI("https://ec2.us-east-1.amazonaws.com/")));
        assertEquals(Region.US_WEST_1, EC2Cloud.getBootstrapRegion(new URI("https://ec2.us-west-1.amazonaws.com/")));
        assertEquals(
                Region.US_GOV_EAST_1, EC2Cloud.getBootstrapRegion(new URI("https://ec2.us-gov-east-1.amazonaws.com/")));
        assertEquals(
                Region.US_GOV_WEST_1, EC2Cloud.getBootstrapRegion(new URI("https://ec2.us-gov-west-1.amazonaws.com/")));
    }

    @Test
    void testInstanceCap() {
        EC2Cloud cloud = new EC2Cloud(
                "us-east-1",
                true,
                "abc",
                "us-east-1",
                null,
                "key",
                null,
                Collections.emptyList(),
                "roleArn",
                "roleSessionName");
        assertEquals(Integer.MAX_VALUE, cloud.getInstanceCap());
        assertEquals("", cloud.getInstanceCapStr());

        final int cap = 3;
        final String capStr = String.valueOf(cap);
        cloud = new EC2Cloud(
                "us-east-1",
                true,
                "abc",
                "us-east-1",
                null,
                "key",
                capStr,
                Collections.emptyList(),
                "roleArn",
                "roleSessionName");
        assertEquals(cap, cloud.getInstanceCap());
        assertEquals(cloud.getInstanceCapStr(), capStr);
    }

    @Test
    void testSpotInstanceCount() throws Exception {
        final int numberOfSpotInstanceRequests = 105;
        EC2Cloud cloud = Mockito.spy(new EC2Cloud(
                "us-east-1",
                true,
                "abc",
                "us-east-1",
                null,
                "key",
                null,
                Collections.emptyList(),
                "roleArn",
                "roleSessionName"));
        Jenkins jenkinsMock = mock(Jenkins.class);
        EC2SpotSlave spotSlaveMock = mock(EC2SpotSlave.class);
        try (MockedStatic<Jenkins> mocked = Mockito.mockStatic(Jenkins.class)) {
            mocked.when(Jenkins::get).thenReturn(jenkinsMock);
            Mockito.when(jenkinsMock.getNodes()).thenReturn(Collections.singletonList(spotSlaveMock));
            when(spotSlaveMock.getSpotRequest()).thenReturn(null);
            when(spotSlaveMock.getSpotInstanceRequestId()).thenReturn("sir-id");

            List<Instance> instances = new ArrayList<>();
            for (int i = 0; i <= numberOfSpotInstanceRequests; i++) {
                instances.add(Instance.builder()
                        .instanceId("id" + i)
                        .tags(Tag.builder()
                                .key("jenkins_slave_type")
                                .value("spot")
                                .build())
                        .build());
            }

            AmazonEC2FactoryMockImpl.instances = instances;

            Mockito.doReturn(AmazonEC2FactoryMockImpl.createAmazonEC2Mock(null))
                    .when(cloud)
                    .connect();

            Method countCurrentEC2SpotSlaves = EC2Cloud.class.getDeclaredMethod(
                    "countCurrentEC2SpotSlaves", SlaveTemplate.class, String.class, Set.class);
            countCurrentEC2SpotSlaves.setAccessible(true);
            Object[] params = {null, "jenkinsurl", new HashSet<String>()};
            int n = (int) countCurrentEC2SpotSlaves.invoke(cloud, params);

            // Should equal number of spot instance requests + 1 for spot nodes not having a spot instance request
            assertEquals(numberOfSpotInstanceRequests + 1, n);
        }
    }

    @Test
    void testSlaveTemplateAddition() throws Exception {
        EC2Cloud cloud = new EC2Cloud(
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
        SlaveTemplate orig = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
                false,
                "ttt",
                Node.Mode.NORMAL,
                "description",
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
        cloud.addTemplate(orig);
        assertNotNull(cloud.getTemplate(orig.description));
    }

    @Test
    void testSlaveTemplateUpdate() throws Exception {
        EC2Cloud cloud = new EC2Cloud(
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
        SlaveTemplate oldSlaveTemplate = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
                false,
                "ttt",
                Node.Mode.NORMAL,
                "OldSlaveDescription",
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
        SlaveTemplate secondSlaveTemplate = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
                false,
                "ttt",
                Node.Mode.NORMAL,
                "SecondSlaveDescription",
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
        cloud.addTemplate(oldSlaveTemplate);
        cloud.addTemplate(secondSlaveTemplate);
        SlaveTemplate newSlaveTemplate = new SlaveTemplate(
                "ami-456",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
                false,
                "ttt",
                Node.Mode.NORMAL,
                "NewSlaveDescription",
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
        int index = cloud.getTemplates().indexOf(oldSlaveTemplate);

        cloud.updateTemplate(newSlaveTemplate, "OldSlaveDescription");
        assertNull(cloud.getTemplate("OldSlaveDescription"));
        assertNotNull(cloud.getTemplate("NewSlaveDescription"));
        assertEquals(index, cloud.getTemplates().indexOf(newSlaveTemplate)); // assert order of templates is kept
    }
}
