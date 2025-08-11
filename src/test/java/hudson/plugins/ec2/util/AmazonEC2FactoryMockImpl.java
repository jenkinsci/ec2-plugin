package hudson.plugins.ec2.util;

import static org.mockito.Mockito.mock;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.plugins.ec2.EC2Cloud;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotInstanceRequestsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateChange;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.SpotInstanceRequest;
import software.amazon.awssdk.services.ec2.model.SpotInstanceState;
import software.amazon.awssdk.services.ec2.model.SpotInstanceType;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesResponse;

@Extension(ordinal = 100)
public class AmazonEC2FactoryMockImpl implements AmazonEC2Factory {

    /**
     * public static to allow supplying own mock to tests.
     */
    public static Ec2Client mock;

    public static List<Instance> instances;

    /**
     * public static to provide a default mock for modifications from tests.
     *
     * @return mocked AmazonEC2
     */
    public static Ec2Client createAmazonEC2Mock() {
        instances = new ArrayList<>(); // Reset for each new mock. In the real world, the client is stateless, but this
        // is convenient for testing.
        return createAmazonEC2Mock(null);
    }

    /**
     * public static to provide a default mock for modifications from tests.
     *
     * @param defaultAnswer
     *            nullable
     * @return mocked AmazonEC2
     */
    public static Ec2Client createAmazonEC2Mock(@Nullable Answer defaultAnswer) {
        Ec2Client mock;
        if (defaultAnswer != null) {
            mock = mock(Ec2Client.class, defaultAnswer);
        } else {
            mock = mock(Ec2Client.class);
            mockDescribeRegions(mock);
            mockDescribeInstances(mock);
            mockDescribeImages(mock);
            mockDescribeKeyPairs(mock);
            mockDescribeSecurityGroups(mock);
            mockDescribeSubnets(mock);
            mockRunInstances(mock);
            mockTerminateInstances(mock);
            mockDescribeSpotInstanceRequests(mock);
        }
        return mock;
    }

    private static void mockDescribeRegions(Ec2Client mock) {
        DescribeRegionsResponse describeRegionsResultMock = mock(DescribeRegionsResponse.class);
        Mockito.doReturn(Collections.singletonList(software.amazon.awssdk.services.ec2.model.Region.builder()
                        .regionName(EC2Cloud.DEFAULT_EC2_HOST)
                        .build()))
                .when(describeRegionsResultMock)
                .regions();
        Mockito.doReturn(describeRegionsResultMock).when(mock).describeRegions();
    }

    private static void mockDescribeInstances(Ec2Client mock) {
        Mockito.doCallRealMethod().when(mock).describeInstances(); // This will just pass on to
        // describeInstances(describeInstancesRequest)
        Mockito.doAnswer(invocationOnMock -> {
                    DescribeInstancesRequest request = invocationOnMock.getArgument(0);
                    if (request.instanceIds() != null && !request.instanceIds().isEmpty()) {
                        return DescribeInstancesResponse.builder()
                                .reservations(Reservation.builder()
                                        .instances(instances.stream()
                                                .filter(instance ->
                                                        request.instanceIds().contains(instance.instanceId()))
                                                .collect(Collectors.toList()))
                                        .build())
                                .build();
                    }
                    return DescribeInstancesResponse.builder()
                            .reservations(
                                    Reservation.builder().instances(instances).build())
                            .build();
                })
                .when(mock)
                .describeInstances(Mockito.any(DescribeInstancesRequest.class));
    }

    private static void mockDescribeSpotInstanceRequests(Ec2Client mock) {
        DescribeSpotInstanceRequestsResponse.Builder describeSpotInstanceRequestsResultBuilder =
                DescribeSpotInstanceRequestsResponse.builder();
        List<SpotInstanceRequest> spotInstanceRequests = new ArrayList<>();
        for (Instance instance : instances) {
            SpotInstanceRequest spotInstanceRequest = SpotInstanceRequest.builder()
                    .instanceId(instance.instanceId())
                    .tags(instance.tags())
                    .state(SpotInstanceState.ACTIVE)
                    .type(SpotInstanceType.ONE_TIME)
                    .build();
            spotInstanceRequests.add(spotInstanceRequest);
        }

        Mockito.doAnswer(invocationOnMock -> {
                    DescribeSpotInstanceRequestsRequest request = invocationOnMock.getArgument(0);
                    int paginationSize = request.maxResults();
                    if (paginationSize == 0) {
                        paginationSize = instances.size();
                    }
                    if (instances.size() > paginationSize && request.nextToken() == null) {
                        describeSpotInstanceRequestsResultBuilder.nextToken("token");
                        describeSpotInstanceRequestsResultBuilder.spotInstanceRequests(
                                spotInstanceRequests.subList(0, 100));
                    } else if (instances.size() <= paginationSize) {
                        describeSpotInstanceRequestsResultBuilder.spotInstanceRequests(spotInstanceRequests);
                        describeSpotInstanceRequestsResultBuilder.nextToken(null);
                    } else {
                        describeSpotInstanceRequestsResultBuilder.spotInstanceRequests(
                                spotInstanceRequests.subList(100, spotInstanceRequests.size() - 1));
                        describeSpotInstanceRequestsResultBuilder.nextToken(null);
                    }
                    return describeSpotInstanceRequestsResultBuilder.build();
                })
                .when(mock)
                .describeSpotInstanceRequests(Mockito.any(DescribeSpotInstanceRequestsRequest.class));
    }

    private static void mockDescribeImages(Ec2Client mock) {
        Mockito.doAnswer(invocationOnMock -> {
                    DescribeImagesRequest request = invocationOnMock.getArgument(0);
                    return DescribeImagesResponse.builder()
                            .images(request.imageIds().stream()
                                    .map(AmazonEC2FactoryMockImpl::createMockImage)
                                    .collect(Collectors.toList()))
                            .build();
                })
                .when(mock)
                .describeImages(Mockito.any(DescribeImagesRequest.class));
    }

    private static Image createMockImage(String amiId) {
        return Image.builder()
                .imageId(amiId)
                .rootDeviceType("ebs")
                .blockDeviceMappings(BlockDeviceMapping.builder()
                        .deviceName("/dev/null")
                        .ebs(EbsBlockDevice.builder().build())
                        .build())
                .build();
    }

    private static void mockDescribeKeyPairs(Ec2Client mock) {
        Mockito.doAnswer(invocationOnMock -> {
                    KeyPairInfo keyPairInfo = KeyPairInfo.builder()
                            .keyFingerprint(Jenkins.get()
                                    .clouds
                                    .get(EC2Cloud.class)
                                    .resolvePrivateKey()
                                    .getFingerprint())
                            .build();
                    return DescribeKeyPairsResponse.builder()
                            .keyPairs(keyPairInfo)
                            .build();
                })
                .when(mock)
                .describeKeyPairs();
    }

    private static void mockDescribeSecurityGroups(Ec2Client mock) {
        Mockito.doAnswer(invocationOnMock -> DescribeSecurityGroupsResponse.builder()
                        .securityGroups(
                                SecurityGroup.builder().vpcId("whatever").build())
                        .build())
                .when(mock)
                .describeSecurityGroups(Mockito.any(DescribeSecurityGroupsRequest.class));
    }

    private static void mockDescribeSubnets(Ec2Client mock) {
        Mockito.doAnswer(invocationOnMock -> DescribeSubnetsResponse.builder()
                        .subnets(Subnet.builder().build())
                        .build())
                .when(mock)
                .describeSubnets(Mockito.any(DescribeSubnetsRequest.class));
    }

    private static void mockRunInstances(Ec2Client mock) {
        Mockito.doAnswer(invocationOnMock -> {
                    RunInstancesRequest request = invocationOnMock.getArgument(0);
                    List<Tag> tags = request.tagSpecifications().stream()
                            .map(TagSpecification::tags)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());

                    List<Instance> localInstances = new ArrayList<>();

                    for (int i = 0; i < request.maxCount(); i++) {
                        Instance instance = Instance.builder()
                                .instanceId(String.valueOf(Math.random()))
                                .instanceType(request.instanceType())
                                .imageId(request.imageId())
                                .tags(tags)
                                .state(InstanceState.builder()
                                        .name(InstanceStateName.RUNNING)
                                        .build())
                                .launchTime(Instant.now())
                                .build();

                        localInstances.add(instance);
                    }

                    instances.addAll(localInstances);

                    return RunInstancesResponse.builder()
                            .reservationId(Reservation.builder()
                                    .instances(localInstances)
                                    .build()
                                    .reservationId())
                            .instances(localInstances)
                            .build();
                })
                .when(mock)
                .runInstances(Mockito.any(RunInstancesRequest.class));
    }

    private static void mockTerminateInstances(Ec2Client mock) {
        Mockito.doAnswer(invocationOnMock -> {
                    TerminateInstancesRequest request = invocationOnMock.getArgument(0);
                    List<Instance> instancesToRemove = new ArrayList<>();
                    request.instanceIds().forEach(instanceId -> instances.stream()
                            .filter(instance -> instance.instanceId().equals(instanceId))
                            .findFirst()
                            .ifPresent(instancesToRemove::add));
                    instances.removeAll(instancesToRemove);
                    return TerminateInstancesResponse.builder()
                            .terminatingInstances(instancesToRemove.stream()
                                    .map(instance -> InstanceStateChange.builder()
                                            .instanceId(instance.instanceId())
                                            .previousState(InstanceState.builder()
                                                    .name(InstanceStateName.STOPPING)
                                                    .build())
                                            .currentState(InstanceState.builder()
                                                    .name(InstanceStateName.TERMINATED)
                                                    .build())
                                            .build())
                                    .collect(Collectors.toList()))
                            .build();
                })
                .when(mock)
                .terminateInstances(Mockito.any(TerminateInstancesRequest.class));
    }

    @Override
    public Ec2Client connect(AwsCredentialsProvider credentialsProvider, Region region, URI endpoint) {
        if (mock == null) {
            mock = createAmazonEC2Mock();
        }
        return mock;
    }
}
