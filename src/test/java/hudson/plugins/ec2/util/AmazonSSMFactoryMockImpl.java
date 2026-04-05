package hudson.plugins.ec2.util;

import static org.mockito.Mockito.mock;

import hudson.Extension;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;

@Extension(ordinal = 100)
public class AmazonSSMFactoryMockImpl implements AmazonSSMFactory {

    public static SsmClient mock;

    public static SsmClient createSsmClientMock() {
        return mock(SsmClient.class);
    }

    @Override
    public SsmClient connect(AwsCredentialsProvider credentialsProvider, Region region, URI endpoint) {
        if (mock == null) {
            mock = createSsmClientMock();
        }
        return mock;
    }
}
