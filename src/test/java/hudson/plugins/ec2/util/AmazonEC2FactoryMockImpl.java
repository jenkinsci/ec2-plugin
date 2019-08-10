package hudson.plugins.ec2.util;

import java.net.URL;
import java.util.Collections;

import javax.annotation.Nullable;

import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeRegionsResult;
import com.amazonaws.services.ec2.model.Region;

import hudson.Extension;
import hudson.plugins.ec2.EC2Cloud;

@Extension
public class AmazonEC2FactoryMockImpl implements AmazonEC2Factory {

    /**
     * public static to allow supplying own mock to tests.
     */
    public static AmazonEC2 mock;

    /**
     * public static to provide a default mock for modifications from tests.
     *
     * @return mocked AmazonEC2
     */
    public static AmazonEC2 createAmazonEC2Mock() {
        return createAmazonEC2Mock(null);
    }

    /**
     * public static to provide a default mock for modifications from tests.
     *
     * @param defaultAnswer
     *            nullable
     * @return mocked AmazonEC2
     */
    public static AmazonEC2 createAmazonEC2Mock(@Nullable Answer defaultAnswer) {
        AmazonEC2 mock;
        if (defaultAnswer != null) {
            mock = Mockito.mock(AmazonEC2.class, defaultAnswer);
        } else {
            mock = Mockito.mock(AmazonEC2.class);
        }
        Mockito.doReturn(createDescribeRegionsResultMock()).when(mock).describeRegions();
        return mock;
    }

    private static DescribeRegionsResult createDescribeRegionsResultMock() {
        DescribeRegionsResult mock = Mockito.mock(DescribeRegionsResult.class);
        Mockito.doReturn(Collections.singletonList(new Region().withRegionName(EC2Cloud.DEFAULT_EC2_HOST))).when(mock).getRegions();
        return mock;
    }

    @Override
    public AmazonEC2 connect(AWSCredentialsProvider credentialsProvider, URL ec2Endpoint) {
        if (mock == null) {
            mock = createAmazonEC2Mock();
        }
        return mock;
    }
}
