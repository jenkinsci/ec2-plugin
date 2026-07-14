package hudson.plugins.ec2.util;

import hudson.Extension;
import hudson.plugins.ec2.EC2Cloud;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;

@Extension
public class AmazonSSMFactoryImpl implements AmazonSSMFactory {

    @Override
    public SsmClient connect(AwsCredentialsProvider credentialsProvider, Region region, URI endpoint) {
        SsmClientBuilder ssmClientBuilder = SsmClient.builder()
                .credentialsProvider(credentialsProvider)
                .httpClient(EC2Cloud.getHttpClient())
                .overrideConfiguration(EC2Cloud.createClientOverrideConfiguration());
        if (region != null) {
            ssmClientBuilder.region(region);
        }
        if (endpoint != null) {
            ssmClientBuilder.endpointOverride(endpoint);
        }
        return ssmClientBuilder.build();
    }
}
