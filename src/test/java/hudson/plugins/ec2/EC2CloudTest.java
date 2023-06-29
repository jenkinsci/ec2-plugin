package hudson.plugins.ec2;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import hudson.model.Node;
import jenkins.model.Jenkins;
import org.junit.Test;
import org.junit.runner.RunWith;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

@RunWith(MockitoJUnitRunner.class)
public class EC2CloudTest {

    @Test
    public void testReattachOrphanStoppedNodes() throws Exception {
        /* Mocked items */
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true,
                "abc", "us-east-1", null, "ghi",
                "3", Collections.emptyList(), "roleArn", "roleSessionName");
        EC2Cloud spyCloud = Mockito.spy(cloud);
        AmazonEC2 mockEc2 = Mockito.mock(AmazonEC2.class);
        Jenkins mockJenkins = Mockito.mock(Jenkins.class);
        EC2AbstractAgent mockOrphanNode = Mockito.mock(EC2AbstractAgent.class);
        AgentTemplate mockAgentTemplate = Mockito.mock(AgentTemplate.class);
        DescribeInstancesResult mockedDIResult = Mockito.mock(DescribeInstancesResult.class);
        Instance mockedInstance = Mockito.mock(Instance.class);
        List<Instance> listOfMockedInstances = new ArrayList<>();
        listOfMockedInstances.add(mockedInstance);


        try (MockedStatic<Jenkins> mocked = Mockito.mockStatic(Jenkins.class)) {
        mocked.when(Jenkins::getInstanceOrNull).thenReturn(mockJenkins);
        EC2AbstractAgent[] orphanNodes = {mockOrphanNode};
        Mockito.doReturn(Arrays.asList(orphanNodes)).when(mockAgentTemplate).toAgents(eq(listOfMockedInstances));
        List<Node> listOfJenkinsNodes = new ArrayList<>();

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
        Mockito.doReturn(mockedDIResult).when(mockAgentTemplate).getDescribeInstanceResult(Mockito.any(AmazonEC2.class), eq(true));
        Mockito.doReturn(listOfMockedInstances).when(mockAgentTemplate).findOrphansOrStopped(eq(mockedDIResult), Mockito.anyInt());
        Mockito.doNothing().when(mockAgentTemplate).wakeOrphansOrStoppedUp(Mockito.any(AmazonEC2.class), eq(listOfMockedInstances));

        /* Actual call to test*/
        spyCloud.attemptReattachOrphanOrStoppedNodes(mockJenkins, mockAgentTemplate, 1);

        /* Checks */
        Mockito.verify(mockAgentTemplate, times(1)).wakeOrphansOrStoppedUp(Mockito.any(AmazonEC2.class), eq(listOfMockedInstances));
        Node[] expectedNodes = {mockOrphanNode};
        assertArrayEquals(expectedNodes, listOfJenkinsNodes.toArray());
        }
    }
}
