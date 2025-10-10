package hudson.plugins.ec2.monitoring;

import java.time.Instant;

/**
 * Data model for AWS EC2 provisioning events to be sent to Snowhouse database.
 * Contains all the required information for monitoring provisioning issues.
 */
public class ProvisioningEvent {
    private final String region;
    private final String availabilityZone;
    private final String requestId;
    private final String requestedInstanceType;
    private final int requestedMaxCount;
    private final int requestedMinCount;
    private final int provisionedInstancesCount;
    private final String controllerName;
    private final String cloudName;
    private final Instant timestamp;
    private final String phase; // "REQUEST", "SUCCESS", "FAILURE"
    private final String errorMessage; // null if successful
    private final String jenkinsUrl;

    public ProvisioningEvent(String region, String availabilityZone, String requestId,
                           String requestedInstanceType, int requestedMaxCount, int requestedMinCount,
                           int provisionedInstancesCount, String controllerName, String cloudName, 
                           String phase, String errorMessage, String jenkinsUrl) {
        this.region = region;
        this.availabilityZone = availabilityZone;
        this.requestId = requestId;
        this.requestedInstanceType = requestedInstanceType;
        this.requestedMaxCount = requestedMaxCount;
        this.requestedMinCount = requestedMinCount;
        this.provisionedInstancesCount = provisionedInstancesCount;
        this.controllerName = controllerName;
        this.cloudName = cloudName;
        this.phase = phase;
        this.errorMessage = errorMessage;
        this.jenkinsUrl = jenkinsUrl;
        this.timestamp = Instant.now();
    }

    // Getters
    public String getRegion() { return region; }
    public String getAvailabilityZone() { return availabilityZone; }
    public String getRequestId() { return requestId; }
    public String getRequestedInstanceType() { return requestedInstanceType; }
    public int getRequestedMaxCount() { return requestedMaxCount; }
    public int getRequestedMinCount() { return requestedMinCount; }
    public int getProvisionedInstancesCount() { return provisionedInstancesCount; }
    public String getControllerName() { return controllerName; }
    public String getCloudName() { return cloudName; }
    public Instant getTimestamp() { return timestamp; }
    public String getPhase() { return phase; }
    public String getErrorMessage() { return errorMessage; }
    public String getJenkinsUrl() { return jenkinsUrl; }
} 