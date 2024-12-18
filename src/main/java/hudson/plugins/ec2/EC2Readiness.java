package hudson.plugins.ec2;

import software.amazon.awssdk.core.exception.SdkException;

public interface EC2Readiness {
    boolean isReady();

    String getEc2ReadinessStatus() throws SdkException;
}
