package hudson.plugins.ec2;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import org.mockito.Mockito;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;


import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(PowerMockRunner.class)
@PrepareForTest({JenkinsLocationConfiguration.class, CloudHelper.class, Jenkins.class, SlaveTemplate.class, DescribeInstancesResult.class, Instance.class, EC2AbstractSlave.class})
@PowerMockIgnore({"javax.crypto.*", "org.hamcrest.*", "javax.net.ssl.*", "com.sun.org.apache.xerces.*", "javax.xml.*", "org.xml.*", "javax.management.*"})
public class CloudHelperTest {

    @Mock
    private AmazonEC2Cloud cloud;

    @Before
    public void init() throws Exception {
        cloud = new AmazonEC2Cloud("us-east-1", true,
                "abc", "us-east-1", null, "ghi",
                "3", Collections.emptyList(), "roleArn", "roleSessionName");

        PowerMockito.mockStatic(Thread.class);
    }

    @Test
    public void testGetInstanceHappyPath() throws Exception {
        /* Mocked items */
        EC2Cloud spyCloud = PowerMockito.spy(cloud);
        AmazonEC2 mockEc2 = PowerMockito.mock(AmazonEC2.class);
        DescribeInstancesResult mockedDIResult = PowerMockito.mock(DescribeInstancesResult.class);
        Reservation mockedReservation = PowerMockito.mock(Reservation.class);
        List<Reservation> reservationResults = Collections.singletonList(mockedReservation);
        Instance mockedInstance = PowerMockito.mock(Instance.class);
        List<Instance> instanceResults = Collections.singletonList(mockedInstance);

        PowerMockito.doReturn(mockEc2).when(spyCloud).connect();
        PowerMockito.doReturn(mockedDIResult).when(mockEc2).describeInstances(Mockito.any(DescribeInstancesRequest.class));
        PowerMockito.doReturn(reservationResults).when(mockedDIResult).getReservations();
        PowerMockito.doReturn(instanceResults).when(mockedReservation).getInstances();

        /* Actual call to test*/
        Instance result = CloudHelper.getInstance("test-instance-id", spyCloud);
        assertEquals(mockedInstance, result);
    }

    @Test
    public void testGetInstanceWithRetryHappyPath() throws Exception {
        Instance mockedInstance = PowerMockito.mock(Instance.class);
        PowerMockito.stub(PowerMockito.method(CloudHelper.class, "getInstance")).toReturn(mockedInstance);

        Instance result = CloudHelper.getInstanceWithRetry("test-instance-id", cloud);
        assertEquals(mockedInstance, result);
    }

    @Test
    public void testGetInstanceWithRetryInstanceNotFound() throws Exception {
        /* Mocked items */
        EC2Cloud spyCloud = PowerMockito.spy(cloud);
        AmazonEC2 mockEc2 = PowerMockito.mock(AmazonEC2.class);
        DescribeInstancesResult mockedDIResult = PowerMockito.mock(DescribeInstancesResult.class);
        Reservation mockedReservation = PowerMockito.mock(Reservation.class);
        List<Reservation> reservationResults = Collections.singletonList(mockedReservation);
        Instance mockedInstance = PowerMockito.mock(Instance.class);
        List<Instance> instanceResults = Collections.singletonList(mockedInstance);
        AmazonServiceException amazonServiceException = new AmazonServiceException("test exception");
        amazonServiceException.setErrorCode("InvalidInstanceID.NotFound");

        Answer<DescribeInstancesResult> answerWithRetry = new Answer<DescribeInstancesResult>() {
            private boolean first = true;
            public DescribeInstancesResult answer(InvocationOnMock invocation) throws Throwable {
                if (first) {
                    first = false;
                    throw amazonServiceException;
                }
                return mockedDIResult;
            }
        };

        PowerMockito.doReturn(mockEc2).when(spyCloud).connect();
        PowerMockito.doAnswer(answerWithRetry).when(mockEc2).describeInstances(Mockito.any(DescribeInstancesRequest.class));
        PowerMockito.doReturn(reservationResults).when(mockedDIResult).getReservations();
        PowerMockito.doReturn(instanceResults).when(mockedReservation).getInstances();

        /* Actual call to test*/
        Instance result = CloudHelper.getInstanceWithRetry("test-instance-id", spyCloud);
        assertEquals(mockedInstance, result);
    }

    @Test
    public void testGetInstanceWithRetryRequestExpired() throws Exception {
        /* Mocked items */
        EC2Cloud spyCloud = PowerMockito.spy(cloud);
        AmazonEC2 mockEc2 = PowerMockito.mock(AmazonEC2.class);
        DescribeInstancesResult mockedDIResult = PowerMockito.mock(DescribeInstancesResult.class);
        Reservation mockedReservation = PowerMockito.mock(Reservation.class);
        List<Reservation> reservationResults = Collections.singletonList(mockedReservation);
        Instance mockedInstance = PowerMockito.mock(Instance.class);
        List<Instance> instanceResults = Collections.singletonList(mockedInstance);
        AmazonServiceException amazonServiceException = new AmazonServiceException("test exception");
        amazonServiceException.setErrorCode("RequestExpired");

        Answer<DescribeInstancesResult> answerWithRetry = new Answer<DescribeInstancesResult>() {
            private boolean first = true;

            public DescribeInstancesResult answer(InvocationOnMock invocation) throws Throwable {
                if (first) {
                    first = false;
                    throw amazonServiceException;
                }
                return mockedDIResult;
            }
        };

        PowerMockito.doReturn(mockEc2).when(spyCloud).connect();
        PowerMockito.doAnswer(answerWithRetry).when(mockEc2).describeInstances(Mockito.any(DescribeInstancesRequest.class));
        PowerMockito.doReturn(reservationResults).when(mockedDIResult).getReservations();
        PowerMockito.doReturn(instanceResults).when(mockedReservation).getInstances();

        /* Actual call to test*/
        Instance result = CloudHelper.getInstanceWithRetry("test-instance-id", spyCloud);
        assertEquals(mockedInstance, result);
    }
}