package hudson.plugins.ec2.util;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import hudson.Extension;
import hudson.plugins.ec2.EC2Cloud;
import java.net.URL;

@Extension
public class AmazonEC2FactoryImpl implements AmazonEC2Factory {

    @Override
    public AmazonEC2 connect(AWSCredentialsProvider credentialsProvider, URL ec2Endpoint) {
        AmazonEC2 client =
                new AmazonEC2Client(credentialsProvider, EC2Cloud.createClientConfiguration(ec2Endpoint.getHost()));
        client.setEndpoint(ec2Endpoint.toString());
        return client;
    }
}
