package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;

import java.util.function.Predicate;

/**
 * A predicate for an AmazonEC2Exception to check for an expired request. The exception is considered expired if:
 * <ul>
 *     <li>The exception is an instance of the AmazonEC2Exception</li>
 *     <li>The {@link #STS_SERVICE_NAME} is equal to {@link AmazonEC2Exception#getServiceName()}</li>
 *     <li>The {@link #STS_STATUS_CODE} is equal to {@link AmazonEC2Exception#getStatusCode()}</li>
 *     <li>The {@link #STS_ERROR_CODE} is equal to {@link AmazonEC2Exception#getErrorCode()}</li>
 * </ul>
 */
public class RequestExpiredPredicate implements Predicate<AmazonClientException> {

    private static final String STS_SERVICE_NAME = "AmazonEC2";
    private static final int STS_STATUS_CODE = 400;
    private static final String STS_ERROR_CODE = "RequestExpired";

    @Override
    public boolean test(AmazonClientException clientException) {
        boolean result = false;

        if (clientException instanceof AmazonEC2Exception) {
            AmazonEC2Exception ec2Exception = ((AmazonEC2Exception) clientException);
            result = STS_SERVICE_NAME.equals(ec2Exception.getServiceName()) &&
                    STS_STATUS_CODE == ec2Exception.getStatusCode() &&
                    STS_ERROR_CODE.equals(ec2Exception.getErrorCode());
        }

        return result;
    }
}
