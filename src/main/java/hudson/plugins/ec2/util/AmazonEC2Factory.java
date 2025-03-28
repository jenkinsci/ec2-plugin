package hudson.plugins.ec2.util;

import hudson.ExtensionPoint;
import java.net.URI;
import jenkins.model.Jenkins;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;

public interface AmazonEC2Factory extends ExtensionPoint {

    static AmazonEC2Factory getInstance() {
        AmazonEC2Factory instance = null;
        for (AmazonEC2Factory implementation : Jenkins.get().getExtensionList(AmazonEC2Factory.class)) {
            if (instance != null) {
                throw new IllegalStateException("Multiple implementations of " + AmazonEC2Factory.class.getName()
                        + " found. If overriding, please consider using ExtensionFilter");
            }
            instance = implementation;
        }
        return instance;
    }

    Ec2Client connect(AwsCredentialsProvider credentialsProvider, Region region, URI endpoint);
}
