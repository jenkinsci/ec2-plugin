package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;

public interface EC2Readiness {
    boolean isReady();

    String getEc2ReadinessStatus() throws AmazonClientException;
}
