package hudson.plugins.ec2.util;

import hudson.Extension;
import hudson.plugins.ec2.EC2Cloud;
import java.net.URISyntaxException;
import java.net.URL;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.ec2.Ec2Client;

@Extension
public class AmazonEC2FactoryImpl implements AmazonEC2Factory {

    @Override
    public Ec2Client connect(AwsCredentialsProvider credentialsProvider, URL ec2Endpoint) {
        Ec2Client client;
        try {
            client = Ec2Client.builder()
                    .credentialsProvider(credentialsProvider)
                    .endpointOverride(ec2Endpoint.toURI())
                    .httpClient(EC2Cloud.createHttpClient(ec2Endpoint.getHost()))
                    .overrideConfiguration(EC2Cloud.createClientOverrideConfiguration())
                    .build();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        return client;
    }
}
