package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

public class EC2CleanupOrphanedNodesTest {
    @Test
    public void testCleanupOrphanedAndActiveNodes() {
        // Mock EC2Cloud and Ec2Client
        EC2Cloud cloud = mock(EC2Cloud.class);
        Ec2Client ec2Client = mock(Ec2Client.class);
        when(cloud.connect()).thenReturn(ec2Client);
        when(cloud.isCleanUpOrphanedNodes()).thenReturn(true);

        // Mock three EC2 instances
        Instance orphaned = mock(Instance.class);
        when(orphaned.instanceId()).thenReturn("i-orphaned");
        Tag oldTag = Tag.builder()
                .key(EC2CleanupOrphanedNodes.NODE_EXPIRES_AT_TAG_NAME)
                .value("2024-01-01T00:00:00Z") // old date
                .build();
        when(orphaned.tags()).thenReturn(List.of(oldTag));

        Instance active1 = mock(Instance.class);
        when(active1.instanceId()).thenReturn("i-active1");
        Tag activeTag1 = Tag.builder()
                .key(EC2CleanupOrphanedNodes.NODE_EXPIRES_AT_TAG_NAME)
                .value(OffsetDateTime.now(ZoneOffset.UTC).toString())
                .build();
        when(active1.tags()).thenReturn(List.of(activeTag1));

        Instance active2 = mock(Instance.class);
        when(active2.instanceId()).thenReturn("i-active2");
        Tag activeTag2 = Tag.builder()
                .key(EC2CleanupOrphanedNodes.NODE_EXPIRES_AT_TAG_NAME)
                .value(OffsetDateTime.now(ZoneOffset.UTC).toString())
                .build();
        when(active2.tags()).thenReturn(List.of(activeTag2));

        // Mock EC2Client describeInstances
        DescribeInstancesResponse response = mock(DescribeInstancesResponse.class);
        Reservation reservation = mock(Reservation.class);
        when(reservation.instances()).thenReturn(List.of(orphaned, active1, active2));
        when(response.reservations()).thenReturn(List.of(reservation));
        when(response.nextToken()).thenReturn(null);
        when(ec2Client.describeInstances((DescribeInstancesRequest) any())).thenReturn(response);
        // Mock Jenkins nodes
        EC2AbstractSlave node1 = mock(EC2AbstractSlave.class);
        when(node1.getInstanceId()).thenReturn("i-active1");
        when(node1.getCloud()).thenReturn(cloud);

        EC2AbstractSlave node2 = mock(EC2AbstractSlave.class);
        when(node2.getInstanceId()).thenReturn("i-active2");
        when(node2.getCloud()).thenReturn(cloud);

        Jenkins jenkins = mock(Jenkins.class);
        MockedStatic<Jenkins> mockedJenkins = Mockito.mockStatic(Jenkins.class);
        mockedJenkins.when(Jenkins::get).thenReturn(jenkins);
        when(jenkins.getNodes()).thenReturn(List.of(node1, node2));

        // Run Orphaned Nodes cleanup
        new EC2CleanupOrphanedNodes().cleanCloud(cloud);

        // Verify terminateInstances was called with the orphaned instance
        ArgumentCaptor<Consumer<TerminateInstancesRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(ec2Client).terminateInstances(captor.capture());

        TerminateInstancesRequest.Builder builder = TerminateInstancesRequest.builder();
        captor.getValue().accept(builder);
        TerminateInstancesRequest actualRequest = builder.build();

        assertThat(
                actualRequest.instanceIds(),
                allOf(hasItem("i-orphaned"), not(hasItem("i-active1")), not(hasItem("i-active2"))));
        mockedJenkins.close();
    }
}
