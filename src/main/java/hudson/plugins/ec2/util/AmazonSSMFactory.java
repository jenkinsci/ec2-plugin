package hudson.plugins.ec2.util;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;

public interface AmazonSSMFactory extends ExtensionPoint {

    static AmazonSSMFactory getInstance() {
        return ExtensionList.lookupFirst(AmazonSSMFactory.class);
    }

    SsmClient connect(AwsCredentialsProvider credentialsProvider, Region region, URI endpoint);
}
