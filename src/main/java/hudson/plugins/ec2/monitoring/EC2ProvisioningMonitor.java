package hudson.plugins.ec2.monitoring;

import hudson.Extension;
import hudson.triggers.SafeTimerTask;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.jenkinsci.plugins.database.Database;
import org.jenkinsci.plugins.database.GlobalDatabaseConfiguration;
import org.json.JSONObject;
import net.snowflake.client.jdbc.SnowflakeConnection;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Monitor for EC2 provisioning events that sends data to Snowhouse database.
 * Implements event queuing and batch processing similar to the Snowflake Jenkins connector.
 */
@Extension
public class EC2ProvisioningMonitor {
    private static final Logger LOG = Logger.getLogger(EC2ProvisioningMonitor.class.getName());
    
    private static final Level LOG_LEVEL = Level.FINE;
    private static final ConcurrentLinkedQueue<String> eventQueue = new ConcurrentLinkedQueue<>();
    
    static {
        // Schedule periodic sending of events to Snowhouse every 30 seconds
        Timer.get().scheduleAtFixedRate(new SafeTimerTask() {
            @Override
            protected void doRun() throws Exception {
                if (eventQueue.size() > 0) {
                    EC2ProvisioningMonitor.sendQueue();
                }
            }
        }, TimeUnit.MINUTES.toMillis(1), TimeUnit.SECONDS.toMillis(30), TimeUnit.MILLISECONDS);
    }

    /**
     * Add a provisioning event to the queue for batch processing.
     */
    public static void recordProvisioningEvent(ProvisioningEvent event) {
        HashMap<String, Object> eventMap = new HashMap<>();
        eventMap.put("timestamp", event.getTimestamp());
        eventMap.put("region", event.getRegion());
        eventMap.put("availability_zone", event.getAvailabilityZone());
        eventMap.put("request_id", event.getRequestId());
        eventMap.put("requested_instance_type", event.getRequestedInstanceType());
        eventMap.put("requested_max_count", event.getRequestedMaxCount());
        eventMap.put("requested_min_count", event.getRequestedMinCount());
        eventMap.put("provisioned_instances_count", event.getProvisionedInstancesCount());
        eventMap.put("controller_name", event.getControllerName());
        eventMap.put("cloud_name", event.getCloudName());
        eventMap.put("phase", event.getPhase());
        eventMap.put("error_message", event.getErrorMessage());
        eventMap.put("jenkins_url", event.getJenkinsUrl());
        
        JSONObject jsonMap = new JSONObject(eventMap);
        enQueue(jsonMap.toString());
    }

    /**
     * Add an event to the queue.
     */
    private static boolean enQueue(String queueItem) {
        boolean retVal = eventQueue.add(queueItem);
        LOG.log(LOG_LEVEL, "EC2 Provisioning event queue size: " + eventQueue.size());
        return retVal;
    }

    /**
     * Send all queued events to Snowhouse database.
     */
    static synchronized void sendQueue() throws Exception {
        if (eventQueue.size() == 0) {
            return;
        }

        Database db = GlobalDatabaseConfiguration.get().getDatabase();
        if (db == null) {
            LOG.log(Level.WARNING, "EC2ProvisioningMonitor failed - no database configured. " +
                    "Discarding " + eventQueue.size() + " events");
            // Drop the existing queue to prevent it from growing forever
            eventQueue.clear();
            return;
        }
        
        Connection con = null;
        PreparedStatement copyStatement = null;
        try {
            long startTime = System.currentTimeMillis();
            ConcurrentLinkedQueue<String> pushQueue = new ConcurrentLinkedQueue<>(eventQueue);
            eventQueue.clear();
            
            LOG.log(Level.INFO, pushQueue.size() + " EC2 provisioning events found in queue");
            LOG.log(LOG_LEVEL, "Fetching database connection");
            
            con = db.getDataSource().getConnection();
            LOG.log(LOG_LEVEL, "Database connection fetched");
            con.createStatement().execute("USE SCHEMA PUBLIC;");
            
            String fileName = Jenkins.get().getRootUrl().replaceAll(
                    "https?://", "").replaceAll("/.*", "").replaceAll(":.*", "") +
                    "_ec2_provisioning.json";
            
            String eventsString = String.join("\n", pushQueue.toArray(new String[0]));
            LOG.log(Level.FINER, "Events being sent: " + eventsString);

            // Upload events to Snowflake automatic table stage
            con.unwrap(SnowflakeConnection.class).uploadStream("@%EC2_PROVISIONING_EVENTS",
                    "ec2_provisioning",
                    new ByteArrayInputStream(eventsString.getBytes()),
                    fileName, true);

            // Copy data from automatic table stage to table (using existing schema)
            String copySql = "COPY INTO EC2_PROVISIONING_EVENTS " +
                    "(CREATE_TIME, REGION, AVAILABILITY_ZONE, REQUEST_ID, REQUESTED_INSTANCE_TYPE, " +
                    "REQUESTED_MAX_COUNT, REQUESTED_MIN_COUNT, PROVISIONED_INSTANCES_COUNT, " +
                    "CONTROLLER_NAME, PHASE, ERROR_MESSAGE, JENKINS_URL, EVENT_DATA) " +
                    "from (select $1:timestamp, $1:region, $1:availability_zone, $1:request_id, " +
                    "$1:requested_instance_type, $1:requested_max_count, $1:requested_min_count, " +
                    "$1:provisioned_instances_count, $1:controller_name, $1:phase, $1:error_message, " +
                    "$1:jenkins_url, $1 from @%EC2_PROVISIONING_EVENTS/ec2_provisioning/" + fileName + ".gz) " +
                    "file_format=(type='json' strip_outer_array=true) " +
                    "on_error='continue' FORCE=TRUE purge=true;";

            LOG.log(LOG_LEVEL, "Executing SQL: " + copySql);

            copyStatement = con.prepareStatement(copySql);
            copyStatement.execute();
            long endTime = System.currentTimeMillis();
            LOG.log(Level.INFO, copyStatement.getUpdateCount() + " EC2 provisioning events inserted in " + 
                    (endTime - startTime) + " ms");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to send EC2 provisioning events to Snowhouse", e);
        } finally {
            if (copyStatement != null) {
                try {
                    copyStatement.close();
                    LOG.log(LOG_LEVEL, "Statement closed");
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to close statement", e);
                }
            }
            if (con != null) {
                try {
                    con.close();
                    LOG.log(LOG_LEVEL, "Connection closed");
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to close connection", e);
                }
            }
        }
    }
} 