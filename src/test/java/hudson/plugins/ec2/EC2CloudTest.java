package hudson.plugins.ec2;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import hudson.model.Node;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

@RunWith(PowerMockRunner.class)
@PrepareForTest({JenkinsLocationConfiguration.class, AmazonEC2.class, Jenkins.class, SlaveTemplate.class, DescribeInstancesResult.class, Instance.class, EC2AbstractSlave.class})
@PowerMockIgnore({"javax.crypto.*", "org.hamcrest.*", "javax.net.ssl.*", "com.sun.org.apache.xerces.*", "javax.xml.*", "org.xml.*", "javax.management.*"})
public class EC2CloudTest {

    @Test
    public void testReattachOrphanStoppedNodes() throws Exception {
        /* Mocked items */
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1",
                "{}", null, Collections.emptyList(),
                "roleArn", "roleSessionName");
        EC2Cloud spyCloud = PowerMockito.spy(cloud);
        AmazonEC2 mockEc2 = PowerMockito.mock(AmazonEC2.class);
        Jenkins mockJenkins = PowerMockito.mock(Jenkins.class);
        EC2AbstractSlave mockOrphanNode = PowerMockito.mock(EC2AbstractSlave.class);
        SlaveTemplate mockSlaveTemplate = PowerMockito.mock(SlaveTemplate.class);
        DescribeInstancesResult mockedDIResult = PowerMockito.mock(DescribeInstancesResult.class);
        Instance mockedInstance = PowerMockito.mock(Instance.class);
        List<Instance> listOfMockedInstances = new ArrayList<>();
        listOfMockedInstances.add(mockedInstance);


        PowerMockito.mockStatic(Jenkins.class);
        Mockito.when(Jenkins.getInstanceOrNull()).thenReturn(mockJenkins);
        EC2AbstractSlave[] orphanNodes = {mockOrphanNode};
        PowerMockito.doReturn(Arrays.asList(orphanNodes)).when(mockSlaveTemplate).toSlaves(eq(listOfMockedInstances));
        List<Node> listOfJenkinsNodes = new ArrayList<>();

        PowerMockito.doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                Node n = (Node) invocation.getArguments()[0];
                listOfJenkinsNodes.add(n);
                return null;
            }
        }).when(mockJenkins).addNode(Mockito.any(Node.class));

        PowerMockito.doReturn(null).when(mockOrphanNode).toComputer();
        PowerMockito.doReturn(false).when(mockOrphanNode).getStopOnTerminate();
        PowerMockito.doReturn(mockEc2).when(spyCloud).connect();
        PowerMockito.doReturn(mockedDIResult).when(mockSlaveTemplate).getDescribeInstanceResult(Mockito.any(AmazonEC2.class), eq(true));
        PowerMockito.doReturn(listOfMockedInstances).when(mockSlaveTemplate).findOrphansOrStopped(eq(mockedDIResult), Mockito.anyInt());
        PowerMockito.doNothing().when(mockSlaveTemplate).wakeOrphansOrStoppedUp(Mockito.any(AmazonEC2.class), eq(listOfMockedInstances));

        /* Actual call to test*/
        spyCloud.attemptReattachOrphanOrStoppedNodes(mockJenkins, mockSlaveTemplate, 1);

        /* Checks */
        Mockito.verify(mockSlaveTemplate, times(1)).wakeOrphansOrStoppedUp(Mockito.any(AmazonEC2.class), eq(listOfMockedInstances));
        Node[] expectedNodes = {mockOrphanNode};
        assertArrayEquals(expectedNodes, listOfJenkinsNodes.toArray());
    }
}
