package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

@ExtendWith(MockitoExtension.class)
class CloudHelperTest {

    @Mock
    private EC2Cloud cloud;

    @BeforeEach
    void init() {
        cloud = new EC2Cloud(
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
    }

    @Test
    void testGetInstanceHappyPath() {
        /* Mocked items */
        EC2Cloud spyCloud = Mockito.spy(cloud);
        Ec2Client mockEc2 = Mockito.mock(Ec2Client.class);
        DescribeInstancesResponse mockedDIResult = Mockito.mock(DescribeInstancesResponse.class);
        Reservation mockedReservation = Mockito.mock(Reservation.class);
        List<Reservation> reservationResults = Collections.singletonList(mockedReservation);
        Instance mockedInstance = Mockito.mock(Instance.class);
        List<Instance> instanceResults = Collections.singletonList(mockedInstance);

        Mockito.doReturn(mockEc2).when(spyCloud).connect();
        Mockito.doReturn(mockedDIResult).when(mockEc2).describeInstances(Mockito.any(DescribeInstancesRequest.class));
        Mockito.doReturn(reservationResults).when(mockedDIResult).reservations();
        Mockito.doReturn(instanceResults).when(mockedReservation).instances();

        /* Actual call to test*/
        Instance result = CloudHelper.getInstance("test-instance-id", spyCloud);
        assertEquals(mockedInstance, result);
    }

    @Test
    void testGetInstanceWithRetryInstanceNotFound() throws Exception {
        /* Mocked items */
        EC2Cloud spyCloud = Mockito.spy(cloud);
        Ec2Client mockEc2 = Mockito.mock(Ec2Client.class);
        DescribeInstancesResponse mockedDIResult = Mockito.mock(DescribeInstancesResponse.class);
        Reservation mockedReservation = Mockito.mock(Reservation.class);
        List<Reservation> reservationResults = Collections.singletonList(mockedReservation);
        Instance mockedInstance = Mockito.mock(Instance.class);
        List<Instance> instanceResults = Collections.singletonList(mockedInstance);
        AwsServiceException amazonServiceException = AwsServiceException.builder()
                .message("test exception")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("InvalidInstanceID.NotFound")
                        .build())
                .build();

        Answer<DescribeInstancesResponse> answerWithRetry = new Answer<>() {
            private boolean first = true;

            @Override
            public DescribeInstancesResponse answer(InvocationOnMock invocation) {
                if (first) {
                    first = false;
                    throw amazonServiceException;
                }
                return mockedDIResult;
            }
        };

        Mockito.doReturn(mockEc2).when(spyCloud).connect();
        Mockito.doAnswer(answerWithRetry).when(mockEc2).describeInstances(Mockito.any(DescribeInstancesRequest.class));
        Mockito.doReturn(reservationResults).when(mockedDIResult).reservations();
        Mockito.doReturn(instanceResults).when(mockedReservation).instances();

        /* Actual call to test*/
        Instance result = CloudHelper.getInstanceWithRetry("test-instance-id", spyCloud);
        assertEquals(mockedInstance, result);
    }

    @Test
    void testGetInstanceWithRetryRequestExpired() throws Exception {
        /* Mocked items */
        EC2Cloud spyCloud = Mockito.spy(cloud);
        Ec2Client mockEc2 = Mockito.mock(Ec2Client.class);
        DescribeInstancesResponse mockedDIResult = Mockito.mock(DescribeInstancesResponse.class);
        Reservation mockedReservation = Mockito.mock(Reservation.class);
        List<Reservation> reservationResults = Collections.singletonList(mockedReservation);
        Instance mockedInstance = Mockito.mock(Instance.class);
        List<Instance> instanceResults = Collections.singletonList(mockedInstance);
        AwsServiceException amazonServiceException = AwsServiceException.builder()
                .message("test exception")
                .awsErrorDetails(
                        AwsErrorDetails.builder().errorCode("RequestExpired").build())
                .build();
        Answer<DescribeInstancesResponse> answerWithRetry = new Answer<>() {
            private boolean first = true;

            @Override
            public DescribeInstancesResponse answer(InvocationOnMock invocation) {
                if (first) {
                    first = false;
                    throw amazonServiceException;
                }
                return mockedDIResult;
            }
        };

        Mockito.doReturn(mockEc2).when(spyCloud).connect();
        Mockito.doAnswer(answerWithRetry).when(mockEc2).describeInstances(Mockito.any(DescribeInstancesRequest.class));
        Mockito.doReturn(reservationResults).when(mockedDIResult).reservations();
        Mockito.doReturn(instanceResults).when(mockedReservation).instances();

        /* Actual call to test*/
        Instance result = CloudHelper.getInstanceWithRetry("test-instance-id", spyCloud);
        assertEquals(mockedInstance, result);
    }
}
