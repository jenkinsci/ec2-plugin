package hudson.plugins.ec2;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateTagsResult;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.Tag;
import hudson.model.Node;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SlaveTemplateUnitTest {

    Logger logger;
    TestHandler handler;

    @Before
    public void setUp() throws Exception {
        AmazonEC2Cloud.testMode = true;

        handler = new TestHandler();
        logger = Logger.getLogger(SlaveTemplate.class.getName());
        logger.addHandler(handler);
    }

    @After
    public void tearDown() throws Exception {
        AmazonEC2Cloud.testMode = false;
    }

    @Test
    public void testUpdateRemoteTags() throws Exception {
        AmazonEC2 ec2 = new AmazonEC2Client() {
            @Override
            public CreateTagsResult createTags(com.amazonaws.services.ec2.model.CreateTagsRequest createTagsRequest) {
                return null;
            }
        };

        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);
        String instanceId = "123";

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, false, "", false, "") {
            @Override
            protected Object readResolve() {
                return null;
            }
        };

        ArrayList<Tag> awsTags = new ArrayList<Tag>();
        awsTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "value1"));
        awsTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "value2"));

        final Object params[] = { ec2, awsTags, "InvalidInstanceRequestID.NotFound", instanceId };
        Whitebox.invokeMethod(orig, "updateRemoteTags", params);
        assertEquals(0, handler.getRecords().size());
    }

    @Test
    public void testUpdateRemoteTagsInstanceNotFound() throws Exception {
        AmazonEC2 ec2 = new AmazonEC2Client() {
            @Override
            public CreateTagsResult createTags(com.amazonaws.services.ec2.model.CreateTagsRequest createTagsRequest) {
                AmazonServiceException e = new AmazonServiceException("Instance not found - InvalidInstanceRequestID.NotFound");
                e.setErrorCode("InvalidInstanceRequestID.NotFound");
                throw e;
            }
        };

        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add(tag1);
        tags.add(tag2);
        String instanceId = "123";

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, false, "", false, "") {
            @Override
            protected Object readResolve() {
                return null;
            }
        };

        ArrayList<Tag> awsTags = new ArrayList<Tag>();
        awsTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "value1"));
        awsTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "value2"));

        final Object params[] = { ec2, awsTags, "InvalidSpotInstanceRequestID.NotFound", instanceId };
        Whitebox.invokeMethod(orig, "updateRemoteTags", params);

        assertEquals(5, handler.getRecords().size());

        Iterator<LogRecord> logs = handler.getRecords().iterator();
        while (logs.hasNext()) {
            String log = logs.next().getMessage();
            assertTrue(log.contains("Instance not found - InvalidInstanceRequestID.NotFound"));
        }

    }
}

class TestHandler extends Handler {
    private final List<LogRecord> records = new LinkedList<LogRecord>();

    @Override
    public void close() throws SecurityException {
    }

    @Override
    public void flush() {
    }

    @Override
    public void publish(LogRecord record) {
        records.add(record);
    }

    public List<LogRecord> getRecords() {
        return records;
    }
}
