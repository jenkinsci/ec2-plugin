package hudson.plugins.ec2;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateTagsResult;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.Tag;
import hudson.model.Node;
import org.junit.Before;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SlaveTemplateUnitTest {

    private Logger logger;
    private TestHandler handler;

    @Before
    public void setUp() throws Exception {
        handler = new TestHandler();
        logger = Logger.getLogger(SlaveTemplate.class.getName());
        logger.addHandler(handler);
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

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, "", false, "",null) {
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

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, description, "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, "", false, "",null) {
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

        for (LogRecord logRecord : handler.getRecords()) {
            String log = logRecord.getMessage();
            assertTrue(log.contains("Instance not found - InvalidInstanceRequestID.NotFound"));
        }
    }

    private void assertMakeDescribeImagesRequestWarning(boolean shouldWarn) {
        boolean foundWarning = false;
        for (LogRecord logRecord : handler.getRecords()) {
            if (!logRecord.getSourceMethodName().equals("makeDescribeImagesRequest")) {
                continue;
            }
            if (logRecord.getLevel() != Level.WARNING) {
                continue;
            }
            if (!logRecord.getMessage().equals("Neither AMI ID nor AMI search attributes provided")) {
                continue;
            }

            foundWarning = true;
        }

        if (shouldWarn) {
            assertTrue("No warning message logged", foundWarning);
        } else {
            assertFalse("Warning message logged", foundWarning);
        }
    }

    private void doTestMakeDescribeImagesRequest(SlaveTemplate template,
                                                 String testImageId,
                                                 String testOwners,
                                                 String testUsers,
                                                 List<EC2Filter> testFilters,
                                                 List<String> expectedImageIds,
                                                 List<String> expectedOwners,
                                                 List<String> expectedUsers,
                                                 List<Filter> expectedFilters,
                                                 boolean shouldWarn) throws Exception {
        handler.clearRecords();
        template.setAmi(testImageId);
        template.setAmiOwners(testOwners);
        template.setAmiUsers(testUsers);
        template.setAmiFilters(testFilters);
        DescribeImagesRequest request = Whitebox.invokeMethod(template,
                                                              "makeDescribeImagesRequest");
        assertEquals(expectedImageIds, request.getImageIds());
        assertEquals(expectedOwners, request.getOwners());
        assertEquals(expectedUsers, request.getExecutableUsers());
        assertEquals(expectedFilters, request.getFilters());
        assertMakeDescribeImagesRequestWarning(shouldWarn);
    }

    @Test
    public void testMakeDescribeImagesRequest() throws Exception {
        SlaveTemplate template = new SlaveTemplate(null, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, false, "ttt", Node.Mode.NORMAL, "foo", "bar", "bbb", "aaa", "10", "fff", null, "-Xmx1g", false, "subnet 456", null, null, false, null, "", true, false, "", false, "") {
            @Override
            protected Object readResolve() {
                return null;
            }
        };

        String testImageId = null;
        String testOwners = null;
        String testUsers = null;
        List<EC2Filter> testFilters = null;
        List<String> expectedImageIds = Collections.emptyList();
        List<String> expectedOwners = Collections.emptyList();
        List<String> expectedUsers = Collections.emptyList();
        List<Filter> expectedFilters = Collections.emptyList();

        // Request will all null search parameters. There should be a
        // warning about requesting an image with no search parameters
        doTestMakeDescribeImagesRequest(template,
                                        testImageId,
                                        testOwners,
                                        testUsers,
                                        testFilters,
                                        expectedImageIds,
                                        expectedOwners,
                                        expectedUsers,
                                        expectedFilters,
                                        true);

        // Try again with empty rather than null parameters. There
        // should be a warning about requesting an image with no search
        // parameters
        testImageId = "";
        testOwners = "";
        testUsers = "";
        testFilters = Collections.emptyList();
        doTestMakeDescribeImagesRequest(template,
                                        testImageId,
                                        testOwners,
                                        testUsers,
                                        testFilters,
                                        expectedImageIds,
                                        expectedOwners,
                                        expectedUsers,
                                        expectedFilters,
                                        true);

        // Set the AMI and not owners or filters
        testImageId = "ami-12345";
        expectedImageIds = Collections.singletonList("ami-12345");
        doTestMakeDescribeImagesRequest(template,
                                        testImageId,
                                        testOwners,
                                        testUsers,
                                        testFilters,
                                        expectedImageIds,
                                        expectedOwners,
                                        expectedUsers,
                                        expectedFilters,
                                        false);

        // Add search criteria
        testOwners = "self";
        testUsers = "self";
        testFilters = Collections.singletonList(new EC2Filter("foo", "bar"));
        expectedOwners = Collections.singletonList("self");
        expectedUsers = Collections.singletonList("self");
        expectedFilters = Collections.singletonList(new Filter("foo",
                                                               Collections.singletonList("bar")));
        doTestMakeDescribeImagesRequest(template,
                                        testImageId,
                                        testOwners,
                                        testUsers,
                                        testFilters,
                                        expectedImageIds,
                                        expectedOwners,
                                        expectedUsers,
                                        expectedFilters,
                                        false);

        // Use search criteria without AMI
        testImageId = null;
        expectedImageIds = Collections.emptyList();
        doTestMakeDescribeImagesRequest(template,
                                        testImageId,
                                        testOwners,
                                        testUsers,
                                        testFilters,
                                        expectedImageIds,
                                        expectedOwners,
                                        expectedUsers,
                                        expectedFilters,
                                        false);

        // Also with AMI blank after trimming
        testImageId = " ";
        doTestMakeDescribeImagesRequest(template,
                                        testImageId,
                                        testOwners,
                                        testUsers,
                                        testFilters,
                                        expectedImageIds,
                                        expectedOwners,
                                        expectedUsers,
                                        expectedFilters,
                                        false);

        // Make sure multiple filters pass through correctly
        testFilters = Arrays.asList(new EC2Filter("foo", "bar"),
                                    new EC2Filter("baz", "blah"));
        expectedFilters = Arrays.asList(new Filter("foo", Collections.singletonList("bar")),
                                        new Filter("baz", Collections.singletonList("blah")));
        doTestMakeDescribeImagesRequest(template,
                                        testImageId,
                                        testOwners,
                                        testUsers,
                                        testFilters,
                                        expectedImageIds,
                                        expectedOwners,
                                        expectedUsers,
                                        expectedFilters,
                                        false);

        // Test various multivalued options to exercise tokenizing
        String[][] testCases = {
            { "self amazon", "self all", "a\\'quote s\\ p\\ a\\ c\\ e\\ s" },
            { "self  amazon", "self  all", "\"a'quote\" \"s p a c e s\"" },
            { " self amazon", " self all", "a\\'quote \"s p a c e s\"" },
            { "self amazon ", "self all ", "\"a'quote\" s\\ p\\ a\\ c\\ e\\ s" },
            { " self  amazon ", " self  all ", " 'a\\'quote' 's p a c e s' " },
        };
        expectedOwners = Arrays.asList("self", "amazon");
        expectedUsers = Arrays.asList("self", "all");
        expectedFilters = Collections.singletonList(new Filter("foo",
                                                               Arrays.asList("a'quote", "s p a c e s")));

        for (String[] entry : testCases) {
            logger.info("Multivalue test entry: [" + String.join(",", entry) + "]");
            testOwners = entry[0];
            testUsers = entry[1];
            testFilters = Collections.singletonList(new EC2Filter("foo", entry[2]));
            doTestMakeDescribeImagesRequest(template,
                                            testImageId,
                                            testOwners,
                                            testUsers,
                                            testFilters,
                                            expectedImageIds,
                                            expectedOwners,
                                            expectedUsers,
                                            expectedFilters,
                                            false);
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

    public void clearRecords() {
        records.clear();
    }
}
