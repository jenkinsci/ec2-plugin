package hudson.plugins.ec2.monitoring;

import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import jenkins.model.Jenkins;

import static org.junit.Assert.*;

/**
 * Test for EC2ProvisioningMonitor functionality.
 */
public class EC2ProvisioningMonitorTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void testProvisioningEventCreation() {
        String region = "us-west-2";
        String az = "us-west-2a";
        String requestId = "test-request-123";
        String instanceType = "m5.large";
        int maxCount = 5;
        int minCount = 1;
        int provisionedCount = 3;
        String controllerName = "test-controller";
        String phase = "SUCCESS";
        String errorMessage = null;
        String jenkinsUrl = "https://jenkins.example.com/";

        ProvisioningEvent event = new ProvisioningEvent(
            region, az, requestId, instanceType, maxCount, minCount,
            provisionedCount, controllerName, "test-cloud", phase, errorMessage, jenkinsUrl
        );

        assertEquals(region, event.getRegion());
        assertEquals(az, event.getAvailabilityZone());
        assertEquals(requestId, event.getRequestId());
        assertEquals(instanceType, event.getRequestedInstanceType());
        assertEquals(maxCount, event.getRequestedMaxCount());
        assertEquals(minCount, event.getRequestedMinCount());
        assertEquals(provisionedCount, event.getProvisionedInstancesCount());
        assertEquals(controllerName, event.getControllerName());
        assertEquals(phase, event.getPhase());
        assertEquals(errorMessage, event.getErrorMessage());
        assertEquals(jenkinsUrl, event.getJenkinsUrl());
        assertNotNull(event.getTimestamp());
    }

    @Test
    public void testProvisioningEventRecording() {
        // Test that recording an event doesn't throw exceptions
        // This test will work even without a database configured
        ProvisioningEvent event = new ProvisioningEvent(
            "us-west-2", "us-west-2a", "test-request-123", "m5.large",
            5, 1, 3, "test-controller", "test-cloud", "SUCCESS", null,
            "https://jenkins.example.com/"
        );

        // This should not throw an exception even without database configuration
        assertDoesNotThrow(() -> {
            EC2ProvisioningMonitor.recordProvisioningEvent(event);
        });
    }

    private void assertDoesNotThrow(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            fail("Expected no exception, but got: " + e.getMessage());
        }
    }
} 