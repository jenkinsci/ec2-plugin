package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;

public interface EC2Readiness {
    public boolean isReady();
    public String getEc2ReadinessStatus() throws AmazonClientException;
}
