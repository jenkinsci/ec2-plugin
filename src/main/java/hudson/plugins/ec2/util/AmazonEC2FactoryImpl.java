package hudson.plugins.ec2.util;

import hudson.Extension;
import hudson.plugins.ec2.EC2Cloud;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.Ec2ClientBuilder;

@Extension
public class AmazonEC2FactoryImpl implements AmazonEC2Factory {

    @Override
    public Ec2Client connect(AwsCredentialsProvider credentialsProvider, Region region, URI endpoint) {
        Ec2ClientBuilder ec2ClientBuilder = Ec2Client.builder()
                .credentialsProvider(credentialsProvider)
                .httpClient(EC2Cloud.getHttpClient())
                .overrideConfiguration(EC2Cloud.createClientOverrideConfiguration());
        if (region != null) {
            ec2ClientBuilder.region(region);
        }
        if (endpoint != null) {
            ec2ClientBuilder.endpointOverride(endpoint);
        }
        return ec2ClientBuilder.build();
    }
}
