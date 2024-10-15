package hudson.plugins.ec2;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import org.mockito.Mockito;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;


import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(MockitoJUnitRunner.class)
public class CloudHelperTest {

    @Mock
    private AmazonEC2Cloud cloud;

    @Before
    public void init() throws Exception {
        cloud = new AmazonEC2Cloud("us-east-1", true,
                "abc", "us-east-1", null, null, "ghi",
                "3", Collections.emptyList(), "roleArn", "roleSessionName");
    }

    @Test
    public void testGetInstanceHappyPath() throws Exception {
        /* Mocked items */
        EC2Cloud spyCloud = Mockito.spy(cloud);
        AmazonEC2 mockEc2 = Mockito.mock(AmazonEC2.class);
        DescribeInstancesResult mockedDIResult = Mockito.mock(DescribeInstancesResult.class);
        Reservation mockedReservation = Mockito.mock(Reservation.class);
        List<Reservation> reservationResults = Collections.singletonList(mockedReservation);
        Instance mockedInstance = Mockito.mock(Instance.class);
        List<Instance> instanceResults = Collections.singletonList(mockedInstance);

        Mockito.doReturn(mockEc2).when(spyCloud).connect();
        Mockito.doReturn(mockedDIResult).when(mockEc2).describeInstances(Mockito.any(DescribeInstancesRequest.class));
        Mockito.doReturn(reservationResults).when(mockedDIResult).getReservations();
        Mockito.doReturn(instanceResults).when(mockedReservation).getInstances();

        /* Actual call to test*/
        Instance result = CloudHelper.getInstance("test-instance-id", spyCloud);
        assertEquals(mockedInstance, result);
    }

    @Test
    public void testGetInstanceWithRetryInstanceNotFound() throws Exception {
        /* Mocked items */
        EC2Cloud spyCloud = Mockito.spy(cloud);
        AmazonEC2 mockEc2 = Mockito.mock(AmazonEC2.class);
        DescribeInstancesResult mockedDIResult = Mockito.mock(DescribeInstancesResult.class);
        Reservation mockedReservation = Mockito.mock(Reservation.class);
        List<Reservation> reservationResults = Collections.singletonList(mockedReservation);
        Instance mockedInstance = Mockito.mock(Instance.class);
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

        Mockito.doReturn(mockEc2).when(spyCloud).connect();
        Mockito.doAnswer(answerWithRetry).when(mockEc2).describeInstances(Mockito.any(DescribeInstancesRequest.class));
        Mockito.doReturn(reservationResults).when(mockedDIResult).getReservations();
        Mockito.doReturn(instanceResults).when(mockedReservation).getInstances();

        /* Actual call to test*/
        Instance result = CloudHelper.getInstanceWithRetry("test-instance-id", spyCloud);
        assertEquals(mockedInstance, result);
    }

    @Test
    public void testGetInstanceWithRetryRequestExpired() throws Exception {
        /* Mocked items */
        EC2Cloud spyCloud = Mockito.spy(cloud);
        AmazonEC2 mockEc2 = Mockito.mock(AmazonEC2.class);
        DescribeInstancesResult mockedDIResult = Mockito.mock(DescribeInstancesResult.class);
        Reservation mockedReservation = Mockito.mock(Reservation.class);
        List<Reservation> reservationResults = Collections.singletonList(mockedReservation);
        Instance mockedInstance = Mockito.mock(Instance.class);
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

        Mockito.doReturn(mockEc2).when(spyCloud).connect();
        Mockito.doAnswer(answerWithRetry).when(mockEc2).describeInstances(Mockito.any(DescribeInstancesRequest.class));
        Mockito.doReturn(reservationResults).when(mockedDIResult).getReservations();
        Mockito.doReturn(instanceResults).when(mockedReservation).getInstances();

        /* Actual call to test*/
        Instance result = CloudHelper.getInstanceWithRetry("test-instance-id", spyCloud);
        assertEquals(mockedInstance, result);
    }
}