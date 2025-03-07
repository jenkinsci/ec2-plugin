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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.Tag;
import hudson.model.Node;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/**
 * Unit tests related to {@link EC2Cloud}, but do not require a Jenkins instance.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class EC2CloudUnitTest {

    @Test
    public void testEC2EndpointURLCreation() throws MalformedURLException {
        EC2Cloud.DescriptorImpl descriptor = new EC2Cloud.DescriptorImpl();

        assertEquals(new URL(EC2Cloud.DEFAULT_EC2_ENDPOINT), descriptor.determineEC2EndpointURL(null));
        assertEquals(new URL(EC2Cloud.DEFAULT_EC2_ENDPOINT), descriptor.determineEC2EndpointURL(""));
        assertEquals(new URL("https://www.abc.com"), descriptor.determineEC2EndpointURL("https://www.abc.com"));
    }

    @Test
    public void testInstaceCap() throws Exception {
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
    public void testSpotInstanceCount() throws Exception {
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
                instances.add(new Instance()
                        .withInstanceId("id" + i)
                        .withTags(new Tag().withKey("jenkins_slave_type").withValue("spot")));
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
    public void testCNPartition() {
        assertEquals(
                "ec2.cn-northwest-1.amazonaws.com.cn", EC2Cloud.getAwsPartitionHostForService("cn-northwest-1", "ec2"));
        assertEquals(
                "s3.cn-northwest-1.amazonaws.com.cn", EC2Cloud.getAwsPartitionHostForService("cn-northwest-1", "s3"));
    }

    @Test
    public void testNormalPartition() {
        assertEquals("ec2.us-east-1.amazonaws.com", EC2Cloud.getAwsPartitionHostForService("us-east-1", "ec2"));
        assertEquals("s3.us-east-1.amazonaws.com", EC2Cloud.getAwsPartitionHostForService("us-east-1", "s3"));
    }

    @Test
    public void testSlaveTemplateAddition() throws Exception {
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
                InstanceType.M1Large,
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
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
        cloud.addTemplate(orig);
        assertNotNull(cloud.getTemplate(orig.description));
    }

    @Test
    public void testSlaveTemplateUpdate() throws Exception {
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
                InstanceType.M1Large,
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
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
        SlaveTemplate secondSlaveTemplate = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
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
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
        cloud.addTemplate(oldSlaveTemplate);
        cloud.addTemplate(secondSlaveTemplate);
        SlaveTemplate newSlaveTemplate = new SlaveTemplate(
                "ami-456",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
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
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
        int index = cloud.getTemplates().indexOf(oldSlaveTemplate);

        cloud.updateTemplate(newSlaveTemplate, "OldSlaveDescription");
        assertNull(cloud.getTemplate("OldSlaveDescription"));
        assertNotNull(cloud.getTemplate("NewSlaveDescription"));
        Assert.assertEquals(index, cloud.getTemplates().indexOf(newSlaveTemplate)); // assert order of templates is kept
    }

    @Test
    public void testReattachOrphanStoppedNodes() throws Exception {
        /* Mocked items */
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
        EC2Cloud spyCloud = Mockito.spy(cloud);
        AmazonEC2 mockEc2 = Mockito.mock(AmazonEC2.class);
        Jenkins mockJenkins = Mockito.mock(Jenkins.class);
        EC2AbstractSlave mockOrphanNode = Mockito.mock(EC2AbstractSlave.class);
        SlaveTemplate mockSlaveTemplate = Mockito.mock(SlaveTemplate.class);
        DescribeInstancesResult mockedDIResult = Mockito.mock(DescribeInstancesResult.class);
        Instance mockedInstance = Mockito.mock(Instance.class);
        List<Instance> listOfMockedInstances = new ArrayList<>();
        listOfMockedInstances.add(mockedInstance);

        try (MockedStatic<Jenkins> mocked = Mockito.mockStatic(Jenkins.class)) {
            mocked.when(Jenkins::getInstanceOrNull).thenReturn(mockJenkins);
            EC2AbstractSlave[] orphanNodes = {mockOrphanNode};
            Mockito.doReturn(Arrays.asList(orphanNodes)).when(mockSlaveTemplate).toSlaves(eq(listOfMockedInstances));
            List<Node> listOfJenkinsNodes = new ArrayList<>();

            Mockito.doAnswer((Answer<Void>) invocation -> {
                        Node n = (Node) invocation.getArguments()[0];
                        listOfJenkinsNodes.add(n);
                        return null;
                    })
                    .when(mockJenkins)
                    .addNode(Mockito.any(Node.class));

            Mockito.doReturn(null).when(mockOrphanNode).toComputer();
            Mockito.doReturn(false).when(mockOrphanNode).getStopOnTerminate();
            Mockito.doReturn(mockEc2).when(spyCloud).connect();
            Mockito.doReturn(mockedDIResult)
                    .when(mockSlaveTemplate)
                    .getDescribeInstanceResult(Mockito.any(AmazonEC2.class), eq(true));
            Mockito.doReturn(listOfMockedInstances)
                    .when(mockSlaveTemplate)
                    .findOrphansOrStopped(eq(mockedDIResult), Mockito.anyInt());
            Mockito.doNothing()
                    .when(mockSlaveTemplate)
                    .wakeOrphansOrStoppedUp(Mockito.any(AmazonEC2.class), eq(listOfMockedInstances));

            /* Actual call to test*/
            spyCloud.attemptReattachOrphanOrStoppedNodes(mockJenkins, mockSlaveTemplate, 1);

            /* Checks */
            Mockito.verify(mockSlaveTemplate, times(1))
                    .wakeOrphansOrStoppedUp(Mockito.any(AmazonEC2.class), eq(listOfMockedInstances));
            Node[] expectedNodes = {mockOrphanNode};
            assertArrayEquals(expectedNodes, listOfJenkinsNodes.toArray());
        }
    }
}
