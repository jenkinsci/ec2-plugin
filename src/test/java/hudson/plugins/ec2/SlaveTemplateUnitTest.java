package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import hudson.model.Node;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

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
        List<EC2Tag> tags = new ArrayList<>();
        tags.add(tag1);
        tags.add(tag2);
        String instanceId = "123";

        SlaveTemplate orig =
                new SlaveTemplate(
                        ami,
                        EC2AbstractSlave.TEST_ZONE,
                        null,
                        "default",
                        "foo",
                        InstanceType.M1Large,
                        false,
                        "ttt",
                        Node.Mode.NORMAL,
                        description,
                        "bar",
                        "bbb",
                        "aaa",
                        "10",
                        "fff",
                        null,
                        "-Xmx1g",
                        false,
                        "subnet 456",
                        tags,
                        null,
                        false,
                        null,
                        "",
                        true,
                        false,
                        "",
                        false,
                        "") {
                    @Override
                    protected Object readResolve() {
                        return null;
                    }
                };

        ArrayList<Tag> awsTags = new ArrayList<>();
        awsTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "value1"));
        awsTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "value2"));

        Method updateRemoteTags = SlaveTemplate.class.getDeclaredMethod(
                "updateRemoteTags", AmazonEC2.class, Collection.class, String.class, String[].class);
        updateRemoteTags.setAccessible(true);
        final Object[] params = {ec2, awsTags, "InvalidInstanceRequestID.NotFound", new String[] {instanceId}};
        updateRemoteTags.invoke(orig, params);
        assertEquals(0, handler.getRecords().size());
    }

    @Test
    public void testUpdateRemoteTagsInstanceNotFound() throws Exception {
        AmazonEC2 ec2 = new AmazonEC2Client() {
            @Override
            public CreateTagsResult createTags(com.amazonaws.services.ec2.model.CreateTagsRequest createTagsRequest) {
                AmazonServiceException e =
                        new AmazonServiceException("Instance not found - InvalidInstanceRequestID.NotFound");
                e.setErrorCode("InvalidInstanceRequestID.NotFound");
                throw e;
            }
        };

        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<>();
        tags.add(tag1);
        tags.add(tag2);
        String instanceId = "123";

        SlaveTemplate orig =
                new SlaveTemplate(
                        ami,
                        EC2AbstractSlave.TEST_ZONE,
                        null,
                        "default",
                        "foo",
                        InstanceType.M1Large,
                        false,
                        "ttt",
                        Node.Mode.NORMAL,
                        description,
                        "bar",
                        "bbb",
                        "aaa",
                        "10",
                        "fff",
                        null,
                        "-Xmx1g",
                        false,
                        "subnet 456",
                        tags,
                        null,
                        false,
                        null,
                        "",
                        true,
                        false,
                        "",
                        false,
                        "") {
                    @Override
                    protected Object readResolve() {
                        return null;
                    }
                };

        ArrayList<Tag> awsTags = new ArrayList<>();
        awsTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "value1"));
        awsTags.add(new Tag(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE, "value2"));

        Method updateRemoteTags = SlaveTemplate.class.getDeclaredMethod(
                "updateRemoteTags", AmazonEC2.class, Collection.class, String.class, String[].class);
        updateRemoteTags.setAccessible(true);
        final Object[] params = {ec2, awsTags, "InvalidSpotInstanceRequestID.NotFound", new String[] {instanceId}};
        updateRemoteTags.invoke(orig, params);

        assertEquals(5, handler.getRecords().size());

        for (LogRecord logRecord : handler.getRecords()) {
            String log = logRecord.getMessage();
            assertTrue(log.contains("Instance not found - InvalidInstanceRequestID.NotFound"));
        }
    }

    private void doTestMakeDescribeImagesRequest(
            SlaveTemplate template,
            String testImageId,
            String testOwners,
            String testUsers,
            List<EC2Filter> testFilters,
            List<String> expectedImageIds,
            List<String> expectedOwners,
            List<String> expectedUsers,
            List<Filter> expectedFilters,
            boolean shouldRaise)
            throws Exception {
        handler.clearRecords();
        template.setAmi(testImageId);
        template.setAmiOwners(testOwners);
        template.setAmiUsers(testUsers);
        template.setAmiFilters(testFilters);
        Method makeDescribeImagesRequest = SlaveTemplate.class.getDeclaredMethod("makeDescribeImagesRequest");
        makeDescribeImagesRequest.setAccessible(true);
        if (shouldRaise) {
            assertThrows(AmazonClientException.class, () -> {
                try {
                    makeDescribeImagesRequest.invoke(template);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        } else {
            DescribeImagesRequest request = (DescribeImagesRequest) makeDescribeImagesRequest.invoke(template);
            assertEquals(expectedImageIds, request.getImageIds());
            assertEquals(expectedOwners, request.getOwners());
            assertEquals(expectedUsers, request.getExecutableUsers());
            assertEquals(expectedFilters, request.getFilters());
        }
    }

    @Test
    public void testMakeDescribeImagesRequest() throws Exception {
        SlaveTemplate template =
                new SlaveTemplate(
                        null,
                        EC2AbstractSlave.TEST_ZONE,
                        null,
                        "default",
                        "foo",
                        InstanceType.M1Large,
                        false,
                        "ttt",
                        Node.Mode.NORMAL,
                        "foo",
                        "bar",
                        "bbb",
                        "aaa",
                        "10",
                        "fff",
                        null,
                        "-Xmx1g",
                        false,
                        "subnet 456",
                        null,
                        null,
                        false,
                        null,
                        "",
                        true,
                        false,
                        "",
                        false,
                        "") {
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

        // Request will all null search parameters. There should be an
        // exception on requesting an image with no search parameters
        doTestMakeDescribeImagesRequest(
                template,
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
        // should be an exception on requesting an image with no search
        // parameters
        testImageId = "";
        testOwners = "";
        testUsers = "";
        testFilters = Collections.emptyList();
        doTestMakeDescribeImagesRequest(
                template,
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
        doTestMakeDescribeImagesRequest(
                template,
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
        expectedFilters = Collections.singletonList(new Filter("foo", Collections.singletonList("bar")));
        doTestMakeDescribeImagesRequest(
                template,
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
        doTestMakeDescribeImagesRequest(
                template,
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
        doTestMakeDescribeImagesRequest(
                template,
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
        testFilters = Arrays.asList(new EC2Filter("foo", "bar"), new EC2Filter("baz", "blah"));
        expectedFilters = Arrays.asList(
                new Filter("foo", Collections.singletonList("bar")),
                new Filter("baz", Collections.singletonList("blah")));
        doTestMakeDescribeImagesRequest(
                template,
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
            {"self amazon", "self all", "a\\'quote s\\ p\\ a\\ c\\ e\\ s"},
            {"self  amazon", "self  all", "\"a'quote\" \"s p a c e s\""},
            {" self amazon", " self all", "a\\'quote \"s p a c e s\""},
            {"self amazon ", "self all ", "\"a'quote\" s\\ p\\ a\\ c\\ e\\ s"},
            {" self  amazon ", " self  all ", " 'a\\'quote' 's p a c e s' "},
        };
        expectedOwners = Arrays.asList("self", "amazon");
        expectedUsers = Arrays.asList("self", "all");
        expectedFilters = Collections.singletonList(new Filter("foo", Arrays.asList("a'quote", "s p a c e s")));

        for (String[] entry : testCases) {
            logger.info("Multivalue test entry: [" + String.join(",", entry) + "]");
            testOwners = entry[0];
            testUsers = entry[1];
            testFilters = Collections.singletonList(new EC2Filter("foo", entry[2]));
            doTestMakeDescribeImagesRequest(
                    template,
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

    private Boolean checkEncryptedForSetupRootDevice(EbsEncryptRootVolume rootVolumeEnum) throws Exception {
        SlaveTemplate template =
                new SlaveTemplate(
                        null,
                        EC2AbstractSlave.TEST_ZONE,
                        null,
                        "default",
                        "foo",
                        InstanceType.M1Large,
                        false,
                        "ttt",
                        Node.Mode.NORMAL,
                        "foo",
                        "bar",
                        "bbb",
                        "aaa",
                        "10",
                        "fff",
                        null,
                        "-Xmx1g",
                        false,
                        "subnet 456",
                        null,
                        null,
                        false,
                        null,
                        "",
                        true,
                        false,
                        "",
                        false,
                        "") {
                    @Override
                    protected Object readResolve() {
                        return null;
                    }
                };
        List deviceMappings = new ArrayList();
        deviceMappings.add(deviceMappings);

        Image image = new Image();
        image.setRootDeviceType("ebs");
        BlockDeviceMapping blockDeviceMapping = new BlockDeviceMapping();
        blockDeviceMapping.setEbs(new EbsBlockDevice());
        image.getBlockDeviceMappings().add(blockDeviceMapping);
        if (rootVolumeEnum instanceof EbsEncryptRootVolume) {
            template.ebsEncryptRootVolume = rootVolumeEnum;
        }
        Method setupRootDevice = SlaveTemplate.class.getDeclaredMethod("setupRootDevice", Image.class, List.class);
        setupRootDevice.setAccessible(true);
        setupRootDevice.invoke(template, image, deviceMappings);
        return image.getBlockDeviceMappings().get(0).getEbs().getEncrypted();
    }

    @Test
    public void testSetupRootDeviceNull() throws Exception {
        Boolean test = checkEncryptedForSetupRootDevice(null);
        Assert.assertNull(test);
    }

    @Test
    public void testSetupRootDeviceDefault() throws Exception {
        Boolean test = checkEncryptedForSetupRootDevice(EbsEncryptRootVolume.DEFAULT);
        Assert.assertNull(test);
    }

    @Test
    public void testSetupRootDeviceNotEncrypted() throws Exception {
        Boolean test = checkEncryptedForSetupRootDevice(EbsEncryptRootVolume.UNENCRYPTED);
        Assert.assertFalse(test);
    }

    @Test
    public void testSetupRootDeviceEncrypted() throws Exception {
        Boolean test = checkEncryptedForSetupRootDevice(EbsEncryptRootVolume.ENCRYPTED);
        Assert.assertTrue(test);
    }

    @Test
    public void testNullTimeoutShouldReturnMaxInt() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                false,
                null,
                "iamInstanceProfile",
                false,
                false,
                null,
                false,
                "");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    @Test
    public void testUpdateAmi() {
        SlaveTemplate st = new SlaveTemplate(
                "ami1",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                false,
                null,
                "iamInstanceProfile",
                false,
                false,
                "0",
                false,
                "");
        assertEquals("ami1", st.getAmi());
        st.setAmi("ami2");
        assertEquals("ami2", st.getAmi());
        st.ami = "ami3";
        assertEquals("ami3", st.getAmi());
    }

    @Test
    public void test0TimeoutShouldReturnMaxInt() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                false,
                null,
                "iamInstanceProfile",
                false,
                false,
                "0",
                false,
                "");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    @Test
    public void testNegativeTimeoutShouldReturnMaxInt() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                false,
                null,
                "iamInstanceProfile",
                false,
                false,
                "-1",
                false,
                "");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    @Test
    public void testNonNumericTimeoutShouldReturnMaxInt() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                false,
                null,
                "iamInstanceProfile",
                false,
                false,
                "NotANumber",
                false,
                "");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    @Test
    public void testAssociatePublicIpSetting() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                false,
                null,
                "iamInstanceProfile",
                false,
                false,
                null,
                true,
                "");
        assertEquals(true, st.getAssociatePublicIp());
    }

    @Test
    public void testConnectUsingPublicIpSetting() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                false,
                null,
                "iamInstanceProfile",
                false,
                false,
                false,
                null,
                true,
                "",
                false,
                true);
        assertEquals(st.connectionStrategy, ConnectionStrategy.PUBLIC_IP);
    }

    @Test
    public void testConnectUsingPublicIpSettingWithDefaultSetting() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                false,
                null,
                "iamInstanceProfile",
                false,
                false,
                null,
                true,
                "");
        assertEquals(st.connectionStrategy, ConnectionStrategy.PUBLIC_IP);
    }

    @Test
    public void testBackwardCompatibleUnixData() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                "22",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "rrr",
                "sudo",
                null,
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                false,
                null,
                "iamInstanceProfile",
                false,
                "NotANumber");
        assertFalse(st.isWindowsSlave());
        assertEquals(22, st.getSshPort());
        assertEquals("sudo", st.getRootCommandPrefix());
    }

    @Test
    public void testChooseSpaceDelimitedSubnetId() throws Exception {
        SlaveTemplate slaveTemplate = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "AMI description",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet-123 subnet-456",
                null,
                null,
                true,
                null,
                "",
                false,
                false,
                "",
                false,
                "");

        String subnet1 = slaveTemplate.chooseSubnetId();
        String subnet2 = slaveTemplate.chooseSubnetId();
        String subnet3 = slaveTemplate.chooseSubnetId();

        assertEquals(subnet1, "subnet-123");
        assertEquals(subnet2, "subnet-456");
        assertEquals(subnet3, "subnet-123");
    }

    @Test
    public void testChooseCommaDelimitedSubnetId() throws Exception {
        SlaveTemplate slaveTemplate = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "AMI description",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet-123,subnet-456",
                null,
                null,
                true,
                null,
                "",
                false,
                false,
                "",
                false,
                "");

        String subnet1 = slaveTemplate.chooseSubnetId();
        String subnet2 = slaveTemplate.chooseSubnetId();
        String subnet3 = slaveTemplate.chooseSubnetId();

        assertEquals(subnet1, "subnet-123");
        assertEquals(subnet2, "subnet-456");
        assertEquals(subnet3, "subnet-123");
    }

    @Test
    public void testChooseSemicolonDelimitedSubnetId() throws Exception {
        SlaveTemplate slaveTemplate = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "AMI description",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet-123;subnet-456",
                null,
                null,
                true,
                null,
                "",
                false,
                false,
                "",
                false,
                "");

        String subnet1 = slaveTemplate.chooseSubnetId();
        String subnet2 = slaveTemplate.chooseSubnetId();
        String subnet3 = slaveTemplate.chooseSubnetId();

        assertEquals(subnet1, "subnet-123");
        assertEquals(subnet2, "subnet-456");
        assertEquals(subnet3, "subnet-123");
    }

    @Issue("JENKINS-59460")
    @Test
    public void testConnectionStrategyDeprecatedFieldsAreExported() {
        SlaveTemplate template = new SlaveTemplate(
                "ami1",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1Large,
                false,
                "ttt",
                Node.Mode.NORMAL,
                "foo ami",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                "-Xmx1g",
                false,
                "subnet 456",
                Collections.singletonList(new EC2Tag("name1", "value1")),
                null,
                false,
                null,
                "",
                true,
                false,
                "",
                false,
                "");

        String exported = Jenkins.XSTREAM.toXML(template);
        assertThat(exported, containsString("usePrivateDnsName"));
        assertThat(exported, containsString("connectUsingPublicIp"));
    }
}

class TestHandler extends Handler {
    private final List<LogRecord> records = new LinkedList<>();

    @Override
    public void close() throws SecurityException {}

    @Override
    public void flush() {}

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
