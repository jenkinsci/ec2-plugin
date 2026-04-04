package hudson.plugins.ec2;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AvailabilityZone;
import software.amazon.awssdk.services.ec2.model.DescribeAvailabilityZonesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

final class CloudHelper {
    private static final Logger LOGGER = Logger.getLogger(CloudHelper.class.getName());

    static Instance getInstanceWithRetry(String instanceId, EC2Cloud cloud) throws SdkException, InterruptedException {
        // Sometimes even after a successful RunInstances, DescribeInstances
        // returns an error for a few seconds. We do a few retries instead of
        // failing instantly. See [JENKINS-15319].
        for (int i = 0; i < 5; i++) {
            try {
                return getInstance(instanceId, cloud);
            } catch (AwsServiceException e) {
                if ("InvalidInstanceID.NotFound".equals(e.awsErrorDetails().errorCode())
                        || EC2Cloud.EC2_REQUEST_EXPIRED_ERROR_CODE.equals(
                                e.awsErrorDetails().errorCode())) {
                    // retry in 5 seconds.
                    Thread.sleep(5000);
                    continue;
                }
                throw e;
            }
        }
        // Last time, throw on any error.
        return getInstance(instanceId, cloud);
    }

    @CheckForNull
    static Instance getInstance(String instanceId, EC2Cloud cloud) throws SdkException {
        if (StringUtils.isEmpty(instanceId) || cloud == null) {
            return null;
        }

        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .instanceIds(Collections.singletonList(instanceId))
                .build();

        List<Reservation> reservations =
                cloud.connect().describeInstances(request).reservations();
        if (reservations.size() != 1) {
            String message = "Unexpected number of reservations reported by EC2 for instance id '" + instanceId
                    + "', expected 1 result, found " + reservations + ".";
            if (reservations.isEmpty()) {
                message += " Instance seems to be dead.";
            }
            LOGGER.info(message);
            throw SdkException.builder().message(message).build();
        }
        Reservation reservation = reservations.get(0);

        List<Instance> instances = reservation.instances();
        if (instances.size() != 1) {
            String message = "Unexpected number of instances reported by EC2 for instance id '" + instanceId
                    + "', expected 1 result, found " + instances + ".";
            if (instances.isEmpty()) {
                message += " Instance seems to be dead.";
            }
            LOGGER.info(message);
            throw SdkException.builder().message(message).build();
        }
        return instances.get(0);
    }

    /**
     * Fetches multiple instances in a single EC2 API call. More efficient than N single-instance calls.
     * Instance IDs not found (e.g. terminated) are omitted from the result.
     */
    static Map<String, Instance> getInstancesBatch(List<String> instanceIds, EC2Cloud cloud) throws SdkException {
        if (instanceIds == null || instanceIds.isEmpty() || cloud == null) {
            return Collections.emptyMap();
        }
        Map<String, Instance> result = new HashMap<>();
        final int chunkSize = 100;
        for (int i = 0; i < instanceIds.size(); i += chunkSize) {
            List<String> chunk = instanceIds.subList(i, Math.min(i + chunkSize, instanceIds.size()));
            DescribeInstancesRequest request =
                    DescribeInstancesRequest.builder().instanceIds(chunk).build();
            for (Reservation r : cloud.connect().describeInstances(request).reservations()) {
                for (Instance inst : r.instances()) {
                    result.put(inst.instanceId(), inst);
                }
            }
        }
        return result;
    }

    @CheckForNull
    static Image getAmiImage(Ec2Client ec2, String ami) {
        List<String> images = Collections.singletonList(ami);
        List<String> owners = Collections.emptyList();
        List<String> users = Collections.emptyList();
        DescribeImagesRequest.Builder requestBuilder = DescribeImagesRequest.builder();
        requestBuilder.imageIds(images);
        requestBuilder.owners(owners);
        requestBuilder.executableUsers(users);
        List<Image> img = ec2.describeImages(requestBuilder.build()).images();
        if (img == null || img.isEmpty()) {
            // de-registered AMI causes an empty list to be
            // returned. so be defensive
            // against other possibilities
            return null;
        } else {
            return img.get(0);
        }
    }

    // Retrieve the availability zones for the region connected on
    static ArrayList<String> getAvailabilityZones(Ec2Client ec2) {
        ArrayList<String> availabilityZones = new ArrayList<>();

        DescribeAvailabilityZonesResponse zones = ec2.describeAvailabilityZones();
        List<AvailabilityZone> zoneList = zones.availabilityZones();

        for (AvailabilityZone z : zoneList) {
            availabilityZones.add(z.zoneName());
        }

        return availabilityZones;
    }
}
