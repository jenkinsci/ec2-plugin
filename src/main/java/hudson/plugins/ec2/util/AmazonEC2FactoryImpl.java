package hudson.plugins.ec2.util;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import hudson.Extension;
import hudson.plugins.ec2.EC2Cloud;
import java.net.URL;

@Extension
public class AmazonEC2FactoryImpl implements AmazonEC2Factory {

    @Override
    public AmazonEC2 connect(AWSCredentialsProvider credentialsProvider, URL ec2Endpoint) {
        AmazonEC2 client = AmazonEC2ClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withClientConfiguration(EC2Cloud.createClientConfiguration(ec2Endpoint.getHost()))
                .build();
        client.setEndpoint(ec2Endpoint.toString());
        return client;
    }
}
