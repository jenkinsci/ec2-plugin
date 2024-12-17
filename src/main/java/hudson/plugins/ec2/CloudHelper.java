package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;

final class CloudHelper {
    private static final Logger LOGGER = Logger.getLogger(CloudHelper.class.getName());

    static Instance getInstanceWithRetry(String instanceId, EC2Cloud cloud)
            throws AmazonClientException, InterruptedException {
        // Sometimes even after a successful RunInstances, DescribeInstances
        // returns an error for a few seconds. We do a few retries instead of
        // failing instantly. See [JENKINS-15319].
        for (int i = 0; i < 5; i++) {
            try {
                return getInstance(instanceId, cloud);
            } catch (AmazonServiceException e) {
                if (e.getErrorCode().equals("InvalidInstanceID.NotFound")
                        || EC2Cloud.EC2_REQUEST_EXPIRED_ERROR_CODE.equals(e.getErrorCode())) {
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
    static Instance getInstance(String instanceId, EC2Cloud cloud) throws AmazonClientException {
        if (StringUtils.isEmpty(instanceId) || cloud == null) {
            return null;
        }

        DescribeInstancesRequest request = new DescribeInstancesRequest();
        request.setInstanceIds(Collections.singletonList(instanceId));

        List<Reservation> reservations =
                cloud.connect().describeInstances(request).getReservations();
        if (reservations.size() != 1) {
            String message = "Unexpected number of reservations reported by EC2 for instance id '" + instanceId
                    + "', expected 1 result, found " + reservations + ".";
            if (reservations.isEmpty()) {
                message += " Instance seems to be dead.";
            }
            LOGGER.info(message);
            throw new AmazonClientException(message);
        }
        Reservation reservation = reservations.get(0);

        List<Instance> instances = reservation.getInstances();
        if (instances.size() != 1) {
            String message = "Unexpected number of instances reported by EC2 for instance id '" + instanceId
                    + "', expected 1 result, found " + instances + ".";
            if (instances.isEmpty()) {
                message += " Instance seems to be dead.";
            }
            LOGGER.info(message);
            throw new AmazonClientException(message);
        }
        return instances.get(0);
    }

    @CheckForNull
    static Image getAmiImage(AmazonEC2 ec2, String ami) {
        List<String> images = Collections.singletonList(ami);
        List<String> owners = Collections.emptyList();
        List<String> users = Collections.emptyList();
        DescribeImagesRequest request = new DescribeImagesRequest();
        request.setImageIds(images);
        request.setOwners(owners);
        request.setExecutableUsers(users);
        List<Image> img = ec2.describeImages(request).getImages();
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
    static ArrayList<String> getAvailabilityZones(AmazonEC2 ec2) {
        ArrayList<String> availabilityZones = new ArrayList<>();

        DescribeAvailabilityZonesResult zones = ec2.describeAvailabilityZones();
        List<AvailabilityZone> zoneList = zones.getAvailabilityZones();

        for (AvailabilityZone z : zoneList) {
            availabilityZones.add(z.getZoneName());
        }

        return availabilityZones;
    }
}
