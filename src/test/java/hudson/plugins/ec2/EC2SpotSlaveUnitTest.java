package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.CreateTagsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsResponse;
import software.amazon.awssdk.services.ec2.model.SpotInstanceRequest;

@WithJenkins
class EC2SpotSlaveUnitTest {

    private JenkinsRule r;
    private Logger logger;
    private TestHandler handler;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        handler = new TestHandler();
        logger = Logger.getLogger(EC2SpotSlave.class.getName());
        logger.addHandler(handler);
    }

    private EC2SpotSlave createSpotSlave(String spotRequestId, List<EC2Tag> tags) throws Exception {
        return new EC2SpotSlave(
                "test-slave",
                spotRequestId,
                "test description",
                "/tmp",
                1,
                hudson.model.Node.Mode.NORMAL,
                "initScript",
                "tmpDir",
                "label",
                Collections.emptyList(),
                "remoteAdmin",
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                "30",
                tags,
                "test-cloud",
                300,
                new UnixData("", null, null, "22", null),
                ConnectionStrategy.PRIVATE_IP,
                -1);
    }

    @Test
    void testGetInstanceIdTagsInstanceOnFulfillment() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        EC2Cloud cloud = mock(EC2Cloud.class);
        when(cloud.connect()).thenReturn(ec2);

        SpotInstanceRequest spotRequest = SpotInstanceRequest.builder()
                .spotInstanceRequestId("sir-12345")
                .instanceId("i-abcdef")
                .build();

        DescribeSpotInstanceRequestsResponse response = DescribeSpotInstanceRequestsResponse.builder()
                .spotInstanceRequests(spotRequest)
                .build();
        when(ec2.describeSpotInstanceRequests(any(DescribeSpotInstanceRequestsRequest.class)))
                .thenReturn(response);
        when(ec2.createTags(any(CreateTagsRequest.class)))
                .thenReturn(CreateTagsResponse.builder().build());

        List<EC2Tag> tags = new ArrayList<>();
        tags.add(new EC2Tag("Name", "my-spot-instance"));
        tags.add(new EC2Tag("Team", "platform"));

        EC2SpotSlave slave = spy(createSpotSlave("sir-12345", tags));
        doReturn(cloud).when(slave).getCloud();

        String instanceId = slave.getInstanceId();

        assertEquals("i-abcdef", instanceId);

        ArgumentCaptor<CreateTagsRequest> captor = ArgumentCaptor.forClass(CreateTagsRequest.class);
        verify(ec2).createTags(captor.capture());

        CreateTagsRequest tagRequest = captor.getValue();
        assertEquals(Collections.singletonList("i-abcdef"), tagRequest.resources());
        assertEquals(2, tagRequest.tags().size());
        assertTrue(tagRequest.tags().stream()
                .anyMatch(t -> "Name".equals(t.key()) && "my-spot-instance".equals(t.value())));
        assertTrue(tagRequest.tags().stream()
                .anyMatch(t -> "Team".equals(t.key()) && "platform".equals(t.value())));
    }

    @Test
    void testGetInstanceIdDoesNotTagWhenInstanceIdEmpty() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        EC2Cloud cloud = mock(EC2Cloud.class);
        when(cloud.connect()).thenReturn(ec2);

        SpotInstanceRequest spotRequest = SpotInstanceRequest.builder()
                .spotInstanceRequestId("sir-12345")
                .instanceId("")
                .build();

        DescribeSpotInstanceRequestsResponse response = DescribeSpotInstanceRequestsResponse.builder()
                .spotInstanceRequests(spotRequest)
                .build();
        when(ec2.describeSpotInstanceRequests(any(DescribeSpotInstanceRequestsRequest.class)))
                .thenReturn(response);

        List<EC2Tag> tags = new ArrayList<>();
        tags.add(new EC2Tag("Name", "my-spot-instance"));

        EC2SpotSlave slave = spy(createSpotSlave("sir-12345", tags));
        doReturn(cloud).when(slave).getCloud();

        String instanceId = slave.getInstanceId();

        assertEquals("", instanceId);
        verify(ec2, never()).createTags(any(CreateTagsRequest.class));
    }

    @Test
    void testGetInstanceIdDoesNotTagWhenSpotRequestNull() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        EC2Cloud cloud = mock(EC2Cloud.class);
        when(cloud.connect()).thenReturn(ec2);

        EC2SpotSlave slave = spy(createSpotSlave(null, Collections.singletonList(new EC2Tag("Name", "test"))));
        doReturn(cloud).when(slave).getCloud();

        String instanceId = slave.getInstanceId();

        assertEquals("", instanceId);
        verify(ec2, never()).createTags(any(CreateTagsRequest.class));
    }

    @Test
    void testGetInstanceIdDoesNotTagWhenTagsEmpty() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        EC2Cloud cloud = mock(EC2Cloud.class);
        when(cloud.connect()).thenReturn(ec2);

        SpotInstanceRequest spotRequest = SpotInstanceRequest.builder()
                .spotInstanceRequestId("sir-12345")
                .instanceId("i-abcdef")
                .build();

        DescribeSpotInstanceRequestsResponse response = DescribeSpotInstanceRequestsResponse.builder()
                .spotInstanceRequests(spotRequest)
                .build();
        when(ec2.describeSpotInstanceRequests(any(DescribeSpotInstanceRequestsRequest.class)))
                .thenReturn(response);

        EC2SpotSlave slave = spy(createSpotSlave("sir-12345", Collections.emptyList()));
        doReturn(cloud).when(slave).getCloud();

        String instanceId = slave.getInstanceId();

        assertEquals("i-abcdef", instanceId);
        verify(ec2, never()).createTags(any(CreateTagsRequest.class));
    }

    @Test
    void testGetInstanceIdHandlesTaggingException() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        EC2Cloud cloud = mock(EC2Cloud.class);
        when(cloud.connect()).thenReturn(ec2);

        SpotInstanceRequest spotRequest = SpotInstanceRequest.builder()
                .spotInstanceRequestId("sir-12345")
                .instanceId("i-abcdef")
                .build();

        DescribeSpotInstanceRequestsResponse response = DescribeSpotInstanceRequestsResponse.builder()
                .spotInstanceRequests(spotRequest)
                .build();
        when(ec2.describeSpotInstanceRequests(any(DescribeSpotInstanceRequestsRequest.class)))
                .thenReturn(response);
        when(ec2.createTags(any(CreateTagsRequest.class)))
                .thenThrow(new RuntimeException("AWS API failure"));

        List<EC2Tag> tags = new ArrayList<>();
        tags.add(new EC2Tag("Name", "my-spot-instance"));

        EC2SpotSlave slave = spy(createSpotSlave("sir-12345", tags));
        doReturn(cloud).when(slave).getCloud();

        String instanceId = slave.getInstanceId();

        assertEquals("i-abcdef", instanceId);
        assertTrue(handler.getRecords().stream()
                .anyMatch(r -> r.getMessage().contains("Failed to tag spot instance i-abcdef")));
    }

    @Test
    void testGetInstanceIdDoesNotRetagWhenAlreadySet() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        EC2Cloud cloud = mock(EC2Cloud.class);
        when(cloud.connect()).thenReturn(ec2);

        List<EC2Tag> tags = new ArrayList<>();
        tags.add(new EC2Tag("Name", "my-spot-instance"));

        EC2SpotSlave slave = spy(createSpotSlave("sir-12345", tags));
        doReturn(cloud).when(slave).getCloud();

        slave.instanceId = "i-already-set";

        String instanceId = slave.getInstanceId();

        assertEquals("i-already-set", instanceId);
        verify(ec2, never()).describeSpotInstanceRequests(any(DescribeSpotInstanceRequestsRequest.class));
        verify(ec2, never()).createTags(any(CreateTagsRequest.class));
    }

    static class TestHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void close() throws SecurityException {}

        @Override
        public void flush() {}

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        public List<LogRecord> getRecords() {
            return records;
        }
    }
}
