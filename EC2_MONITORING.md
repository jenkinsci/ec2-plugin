# EC2 Provisioning Monitoring

This feature provides real-time monitoring for AWS EC2 node provisioning issues by integrating with Snowhouse database using JDBC.

## Overview

The monitoring system captures detailed information about EC2 provisioning attempts and sends them to a Snowhouse database for real-time analysis and alerting. This helps identify provisioning issues faster than relying on application logs and CloudTrail.

## Data Captured

For each provisioning attempt, the following information is recorded:

- **region**: AWS region where provisioning was attempted
- **availability_zone**: Specific AZ within the region
- **request_id**: Unique identifier for the provisioning request
- **requested_instance_type**: EC2 instance type requested (e.g., m5.large)
- **requested_max_count**: Maximum number of instances requested
- **requested_min_count**: Minimum number of instances requested
- **provisioned_instances_count**: Actual number of instances provisioned
- **controller_name**: Name of the Jenkins controller
- **timestamp**: When the event occurred
- **phase**: Event phase (REQUEST, SUCCESS, FAILURE, REQUEST_FALLBACK, SUCCESS_FALLBACK)
- **error_message**: Error details if provisioning failed
- **jenkins_url**: URL of the Jenkins instance

## Database Schema

The monitoring system creates the following table in Snowhouse:

```sql
CREATE TABLE IF NOT EXISTS EC2_PROVISIONING_EVENTS (
    ID NUMBER AUTOINCREMENT,
    CREATE_TIME TIMESTAMP_NTZ,
    REGION VARCHAR(50),
    AVAILABILITY_ZONE VARCHAR(50),
    REQUEST_ID VARCHAR(100),
    REQUESTED_INSTANCE_TYPE VARCHAR(50),
    REQUESTED_MAX_COUNT NUMBER,
    REQUESTED_MIN_COUNT NUMBER,
    PROVISIONED_INSTANCES_COUNT NUMBER,
    CONTROLLER_NAME VARCHAR(200),
    PHASE VARCHAR(50),
    ERROR_MESSAGE VARCHAR(2000),
    JENKINS_URL VARCHAR(500),
    EVENT_DATA VARIANT,
    PRIMARY KEY (ID)
);
```

## Configuration

1. **Install Database Plugin**: The monitoring requires the Jenkins Database plugin to be installed.

2. **Configure Snowhouse Database**: In Jenkins system configuration, add a new Snowflake database connection with:
   - Account Name: Your Snowflake account
   - Database: Target database name
   - Warehouse: Snowflake warehouse to use
   - Credentials: Username/password credentials for Snowflake
   - Timeouts: Network, query, and login timeouts

3. **Set as Global Database**: Configure the Snowflake connection as the global database in Jenkins.

## Event Flow

1. **Provisioning Request**: When Jenkins attempts to provision EC2 instances, a "REQUEST" event is recorded
2. **Success/Failure**: Based on the AWS API response, either "SUCCESS" or "FAILURE" events are recorded
3. **Fallback Scenarios**: For spot instances that fall back to on-demand, additional "REQUEST_FALLBACK" and "SUCCESS_FALLBACK" events are recorded
4. **Batch Processing**: Events are queued and sent to Snowhouse in batches every 30 seconds

## Monitoring Points

The system monitors both:

- **On-demand instances**: Direct EC2 instance provisioning
- **Spot instances**: Spot instance requests and their fallback scenarios

## Benefits

- **Real-time alerting**: Immediate visibility into provisioning issues
- **Trend analysis**: Historical data for capacity planning
- **Failure investigation**: Detailed error information for troubleshooting
- **Performance monitoring**: Track provisioning success rates and response times

## Implementation Details

- **Non-blocking**: Event recording doesn't impact provisioning performance
- **Fault-tolerant**: Monitoring failures don't affect EC2 provisioning
- **Scalable**: Batch processing handles high-volume environments
- **Configurable**: Database connection is configurable through Jenkins UI 