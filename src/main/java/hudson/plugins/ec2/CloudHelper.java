package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import java.util.Collections;
import java.util.List;

final class CloudHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(CloudHelper.class);

    static Instance getInstanceWithRetry(String instanceId, EC2Cloud cloud) throws AmazonClientException, InterruptedException {
        // Sometimes even after a successful RunInstances, DescribeInstances
        // returns an error for a few seconds. We do a few retries instead of
        // failing instantly. See [JENKINS-15319].
        for (int i = 0; i < 5; i++) {
            try {
                return getInstance(instanceId, cloud);
            } catch (AmazonServiceException e) {
                if (e.getErrorCode().equals("InvalidInstanceID.NotFound")) {
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
        if (StringUtils.isEmpty(instanceId) || cloud == null)
            return null;

        DescribeInstancesRequest request = new DescribeInstancesRequest();
        request.setInstanceIds(Collections.<String> singletonList(instanceId));

        List<Reservation> reservations = cloud.connect().describeInstances(request).getReservations();
        if (reservations.size() != 1) {
          String message = "Unexpected number of reservations reported by EC2 for instance id '" + instanceId + "', expected 1 result, found " + reservations + ".";
          if (reservations.size() == 0) {
            message += " Instance seems to be dead.";
          }
          LOGGER.info(message);
          throw new AmazonClientException(message);
        }
        Reservation reservation = reservations.get(0);

        List<Instance> instances = reservation.getInstances();
        if (instances.size() != 1) {
          String message = "Unexpected number of instances reported by EC2 for instance id '" + instanceId + "', expected 1 result, found " + instances + ".";
          if (instances.size() == 0) {
            message += " Instance seems to be dead.";
          }
          LOGGER.info(message);
          throw new AmazonClientException(message);
        }
        return instances.get(0);
    }
}
