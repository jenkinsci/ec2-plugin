package hudson.plugins.ec2;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.model.Node;
import jenkins.model.Jenkins;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

@RunWith(MockitoJUnitRunner.class)
public class EC2CloudTest {

    @Test
    public void testSlaveTemplateAddition() throws Exception {
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true,
                "abc", "us-east-1",null, "ghi",
                "3", Collections.emptyList(), "roleArn", "roleSessionName");
        SlaveTemplate orig = new SlaveTemplate("ami-123", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "description", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, 0, 0, null, "iamInstanceProfile", true, false, "", false, "", false, false, false, ConnectionStrategy.PUBLIC_IP, -1, null, null, Tenancy.Default, EbsEncryptRootVolume.DEFAULT);
        cloud.addTemplate(orig);
        assertNotNull(cloud.getTemplate(orig.description));
    }

    @Test
    public void testReattachOrphanStoppedNodes() throws Exception {
        /* Mocked items */
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true,
                "abc", "us-east-1", null, "ghi",
                "3", Collections.emptyList(), "roleArn", "roleSessionName");
        EC2Cloud spyCloud = Mockito.spy(cloud);
        AmazonEC2 mockEc2 = Mockito.mock(AmazonEC2.class);
        Jenkins mockJenkins = Mockito.mock(Jenkins.class);
        EC2AbstractSlave mockOrphanNode = Mockito.mock(EC2AbstractSlave.class);
        SlaveTemplate mockSlaveTemplate = Mockito.mock(SlaveTemplate.class);
        DescribeInstancesResult mockedDIResult = Mockito.mock(DescribeInstancesResult.class);
        Instance mockedInstance = Mockito.mock(Instance.class);
        List<Instance> listOfMockedInstances = new ArrayList<>();
        listOfMockedInstances.add(mockedInstance);
        List<Node> listOfJenkinsNodes = new ArrayList<>();

        try (MockedStatic<Jenkins> mocked = Mockito.mockStatic(Jenkins.class)) {
            mocked.when(Jenkins::getInstanceOrNull).thenReturn(mockJenkins);
            Mockito.doReturn(null).when(spyCloud).resolveKeyPair();
            EC2AbstractSlave[] orphanNodes = {mockOrphanNode};
            Mockito.doReturn(Arrays.asList(orphanNodes)).when(mockSlaveTemplate).toSlaves(Mockito.any(List.class));

            Mockito.doAnswer(new Answer<Void>() {
                public Void answer(InvocationOnMock invocation) {
                    Node n = (Node) invocation.getArguments()[0];
                    listOfJenkinsNodes.add(n);
                    return null;
                }
            }).when(mockJenkins).addNode(Mockito.any(Node.class));

            Mockito.doReturn(null).when(mockOrphanNode).toComputer();
            Mockito.doReturn(false).when(mockOrphanNode).getStopOnTerminate();
            Mockito.doReturn(mockEc2).when(spyCloud).connect();
            Mockito.doReturn(mockedDIResult).when(mockSlaveTemplate).getDescribeInstanceResult(Mockito.any(AmazonEC2.class), eq(true));
            Mockito.doReturn(listOfMockedInstances).when(mockSlaveTemplate).findOrphansOrStopped(eq(mockedDIResult), Mockito.anyInt());
            Mockito.doNothing().when(mockSlaveTemplate).wakeOrphansOrStoppedUp(Mockito.any(AmazonEC2.class), eq(listOfMockedInstances));

            /* Actual call to test*/
            spyCloud.attemptReattachOrphanOrStoppedNodes(mockJenkins, mockSlaveTemplate, 1);

            /* Checks */
            Mockito.verify(mockSlaveTemplate, times(1)).wakeOrphansOrStoppedUp(Mockito.any(AmazonEC2.class), eq(listOfMockedInstances));
            Node[] expectedNodes = {mockOrphanNode};
            assertArrayEquals(expectedNodes, listOfJenkinsNodes.toArray());
        }
    }
}
