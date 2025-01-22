package hudson.plugins.ec2.util;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import hudson.ExtensionPoint;
import java.net.URL;
import jenkins.model.Jenkins;

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

    AmazonEC2 connect(AWSCredentialsProvider credentialsProvider, URL ec2Endpoint);
}
