package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.CreateTagsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Tag;

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
        Ec2Client ec2 = new Ec2Client() {
            @Override
            public CreateTagsResponse createTags(CreateTagsRequest createTagsRequest) {
                return null;
            }

            @Override
            public void close() {}

            @Override
            public String serviceName() {
                return "AmazonEC2";
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
                        InstanceType.M1_LARGE.toString(),
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
                        EC2AbstractSlave.DEFAULT_JAVA_PATH,
                        "-Xmx1g",
                        false,
                        "subnet 456",
                        tags,
                        null,
                        0,
                        0,
                        null,
                        "",
                        false,
                        true,
                        "",
                        false,
                        "",
                        false,
                        false,
                        false,
                        ConnectionStrategy.PRIVATE_IP,
                        -1,
                        Collections.emptyList(),
                        null,
                        Tenancy.Default,
                        EbsEncryptRootVolume.DEFAULT,
                        EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                        EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                        EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                        EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED) {
                    @Override
                    protected Object readResolve() {
                        return null;
                    }
                };

        ArrayList<Tag> awsTags = new ArrayList<>();
        awsTags.add(Tag.builder()
                .key(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)
                .value("value1")
                .build());
        awsTags.add(Tag.builder()
                .key(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)
                .value("value2")
                .build());

        Method updateRemoteTags = SlaveTemplate.class.getDeclaredMethod(
                "updateRemoteTags", Ec2Client.class, Collection.class, String.class, String[].class);
        updateRemoteTags.setAccessible(true);
        final Object[] params = {ec2, awsTags, "InvalidInstanceRequestID.NotFound", new String[] {instanceId}};
        updateRemoteTags.invoke(orig, params);
        assertEquals(0, handler.getRecords().size());
    }

    @Test
    public void testUpdateRemoteTagsInstanceNotFound() throws Exception {
        Ec2Client ec2 = new Ec2Client() {
            @Override
            public CreateTagsResponse createTags(CreateTagsRequest createTagsRequest) {
                AwsServiceException e = AwsServiceException.builder()
                        .message("Instance not found - InvalidInstanceRequestID.NotFound")
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("InvalidInstanceRequestID.NotFound")
                                .build())
                        .build();
                throw e;
            }

            @Override
            public void close() {}

            @Override
            public String serviceName() {
                return "AmazonEC2";
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
                        InstanceType.M1_LARGE.toString(),
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
                        EC2AbstractSlave.DEFAULT_JAVA_PATH,
                        "-Xmx1g",
                        false,
                        "subnet 456",
                        tags,
                        null,
                        0,
                        0,
                        null,
                        "",
                        false,
                        true,
                        "",
                        false,
                        "",
                        false,
                        false,
                        false,
                        ConnectionStrategy.PRIVATE_IP,
                        -1,
                        Collections.emptyList(),
                        null,
                        Tenancy.Default,
                        EbsEncryptRootVolume.DEFAULT,
                        EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                        EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                        EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                        EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED) {
                    @Override
                    protected Object readResolve() {
                        return null;
                    }
                };

        ArrayList<Tag> awsTags = new ArrayList<>();
        awsTags.add(Tag.builder()
                .key(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)
                .value("value1")
                .build());
        awsTags.add(Tag.builder()
                .key(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)
                .value("value2")
                .build());

        Method updateRemoteTags = SlaveTemplate.class.getDeclaredMethod(
                "updateRemoteTags", Ec2Client.class, Collection.class, String.class, String[].class);
        updateRemoteTags.setAccessible(true);
        final Object[] params = {ec2, awsTags, "InvalidSpotInstanceRequestID.NotFound", new String[] {instanceId}};
        updateRemoteTags.invoke(orig, params);

        assertEquals(5, handler.getRecords().size());

        for (LogRecord logRecord : handler.getRecords()) {
            String log = logRecord.getThrown().getMessage();
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
            assertThrows(SdkException.class, () -> {
                try {
                    makeDescribeImagesRequest.invoke(template);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        } else {
            DescribeImagesRequest request = (DescribeImagesRequest) makeDescribeImagesRequest.invoke(template);
            assertEquals(expectedImageIds, request.imageIds());
            assertEquals(expectedOwners, request.owners());
            assertEquals(expectedUsers, request.executableUsers());
            assertEquals(expectedFilters, request.filters());
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
                        InstanceType.M1_LARGE.toString(),
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
                        EC2AbstractSlave.DEFAULT_JAVA_PATH,
                        "-Xmx1g",
                        false,
                        "subnet 456",
                        null,
                        null,
                        0,
                        0,
                        null,
                        "",
                        false,
                        true,
                        "",
                        false,
                        "",
                        false,
                        false,
                        false,
                        ConnectionStrategy.PRIVATE_IP,
                        -1,
                        Collections.emptyList(),
                        null,
                        Tenancy.Default,
                        EbsEncryptRootVolume.DEFAULT,
                        EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                        EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                        EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                        EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED) {
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
        expectedFilters = Collections.singletonList(Filter.builder()
                .name("foo")
                .values(Collections.singletonList("bar"))
                .build());
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
                Filter.builder()
                        .name("foo")
                        .values(Collections.singletonList("bar"))
                        .build(),
                Filter.builder()
                        .name("baz")
                        .values(Collections.singletonList("blah"))
                        .build());
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
        expectedFilters = Collections.singletonList(Filter.builder()
                .name("foo")
                .values(Arrays.asList("a'quote", "s p a c e s"))
                .build());

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
                        InstanceType.M1_LARGE.toString(),
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
                        EC2AbstractSlave.DEFAULT_JAVA_PATH,
                        "-Xmx1g",
                        false,
                        "subnet 456",
                        null,
                        null,
                        0,
                        0,
                        null,
                        "",
                        false,
                        true,
                        "",
                        false,
                        "",
                        false,
                        false,
                        false,
                        ConnectionStrategy.PRIVATE_IP,
                        -1,
                        Collections.emptyList(),
                        null,
                        Tenancy.Default,
                        EbsEncryptRootVolume.DEFAULT,
                        EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                        EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                        EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                        EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED) {
                    @Override
                    protected Object readResolve() {
                        return null;
                    }
                };
        BlockDeviceMapping blockDeviceMapping = BlockDeviceMapping.builder()
                .ebs(EbsBlockDevice.builder().build())
                .build();
        Image image = Image.builder()
                .rootDeviceType("ebs")
                .blockDeviceMappings(blockDeviceMapping)
                .build();
        List<BlockDeviceMapping> deviceMappings = new ArrayList<>(image.blockDeviceMappings());
        if (rootVolumeEnum instanceof EbsEncryptRootVolume) {
            template.ebsEncryptRootVolume = rootVolumeEnum;
        }
        Method setupRootDevice = SlaveTemplate.class.getDeclaredMethod("setupRootDevice", Image.class, List.class);
        setupRootDevice.setAccessible(true);
        setupRootDevice.invoke(template, image, deviceMappings);
        return deviceMappings.get(0).ebs().encrypted();
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
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                false,
                false,
                null,
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
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
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                false,
                false,
                "0",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
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
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                false,
                false,
                "0",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
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
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                false,
                false,
                "-1",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
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
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                false,
                false,
                "NotANumber",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
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
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                false,
                false,
                null,
                true,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
        assertTrue(st.getAssociatePublicIp());
    }

    @Test
    public void testConnectUsingPublicIpSetting() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                false,
                false,
                null,
                true,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
        assertEquals(ConnectionStrategy.PUBLIC_IP, st.connectionStrategy);
    }

    @Test
    public void testConnectUsingPublicIpSettingWithDefaultSetting() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                false,
                false,
                null,
                true,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PUBLIC_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
        assertEquals(ConnectionStrategy.PUBLIC_IP, st.connectionStrategy);
    }

    @Test
    public void testBackwardCompatibleUnixData() {
        SlaveTemplate st = new SlaveTemplate(
                "",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
                false,
                "ttt",
                Node.Mode.NORMAL,
                "",
                "bar",
                "bbb",
                "aaa",
                "10",
                "rrr",
                new UnixData("sudo", null, null, "22", null),
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                null,
                null,
                0,
                0,
                null,
                "iamInstanceProfile",
                false,
                false,
                "NotANumber",
                false,
                null,
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);
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
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet-123 subnet-456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);

        String subnet1 = slaveTemplate.chooseSubnetId();
        String subnet2 = slaveTemplate.chooseSubnetId();
        String subnet3 = slaveTemplate.chooseSubnetId();

        assertEquals("subnet-123", subnet1);
        assertEquals("subnet-456", subnet2);
        assertEquals("subnet-123", subnet3);
    }

    @Test
    public void testChooseCommaDelimitedSubnetId() throws Exception {
        SlaveTemplate slaveTemplate = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet-123,subnet-456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);

        String subnet1 = slaveTemplate.chooseSubnetId();
        String subnet2 = slaveTemplate.chooseSubnetId();
        String subnet3 = slaveTemplate.chooseSubnetId();

        assertEquals("subnet-123", subnet1);
        assertEquals("subnet-456", subnet2);
        assertEquals("subnet-123", subnet3);
    }

    @Test
    public void testChooseSemicolonDelimitedSubnetId() throws Exception {
        SlaveTemplate slaveTemplate = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "foo",
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet-123;subnet-456",
                null,
                null,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);

        String subnet1 = slaveTemplate.chooseSubnetId();
        String subnet2 = slaveTemplate.chooseSubnetId();
        String subnet3 = slaveTemplate.chooseSubnetId();

        assertEquals("subnet-123", subnet1);
        assertEquals("subnet-456", subnet2);
        assertEquals("subnet-123", subnet3);
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
                InstanceType.M1_LARGE.toString(),
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
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                false,
                "subnet 456",
                Collections.singletonList(new EC2Tag("name1", "value1")),
                null,
                0,
                0,
                null,
                "",
                false,
                true,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED);

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
