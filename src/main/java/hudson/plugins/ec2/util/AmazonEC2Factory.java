package hudson.plugins.ec2.util;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;

public interface AmazonEC2Factory extends ExtensionPoint {

    static AmazonEC2Factory getInstance() {
        return ExtensionList.lookupFirst(AmazonEC2Factory.class);
    }

    Ec2Client connect(AwsCredentialsProvider credentialsProvider, Region region, URI endpoint);
}
