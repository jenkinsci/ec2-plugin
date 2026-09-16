package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.RetryPolicyContext;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

/**
 * A zone that has run out of an instance type has to be reported as soon as EC2 says so.
 *
 * <p>EC2 answers insufficient capacity with a 500, which the SDK reads as the service having a bad
 * moment and retries, and with the retry count this plugin sets that put around two minutes between
 * the launch and the plugin hearing about it. The whole point of spreading a request over the zones
 * is that the next zone is tried while the request is still in hand, and that cannot happen while
 * the SDK is still asking the first one.
 */
class EC2CloudRetryPolicyTest {

    @Test
    void testAZoneOutOfCapacityIsNotAskedAgain() {
        assertThat(shouldRetry(capacityException("InsufficientInstanceCapacity")), equalTo(false));
        assertThat(shouldRetry(capacityException("InsufficientHostCapacity")), equalTo(false));
        assertThat(shouldRetry(capacityException("InsufficientReservedInstancesCapacity")), equalTo(false));
    }

    /**
     * Everything else keeps the retries it had. Throttling and the genuine server errors are what
     * the raised retry count is for.
     */
    @Test
    void testOtherFailuresAreStillRetried() {
        assertThat(shouldRetry(capacityException("RequestLimitExceeded")), equalTo(true));
        assertThat(shouldRetry(capacityException("InternalError")), equalTo(true));
    }

    private static boolean shouldRetry(SdkException failure) {
        RetryPolicy policy =
                EC2Cloud.createClientOverrideConfiguration().retryPolicy().orElseThrow();
        return policy.retryCondition()
                .shouldRetry(RetryPolicyContext.builder()
                        .exception(failure)
                        // The 500 EC2 answers with, which on its own makes the SDK want to retry.
                        .httpStatusCode(500)
                        .retriesAttempted(0)
                        .build());
    }

    /** As EC2 sends it: a 500, which is what makes the SDK want to ask again. */
    private static Ec2Exception capacityException(String errorCode) {
        return (Ec2Exception) Ec2Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
                .statusCode(500)
                .message(errorCode)
                .build();
    }
}
