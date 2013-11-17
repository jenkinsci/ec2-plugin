/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import java.util.ArrayList;
import java.util.List;

import hudson.model.Node;
import hudson.util.FormValidation;

import org.jvnet.hudson.test.HudsonTestCase;

import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.SpotInstanceType;

/**
 * Basic test to validate SlaveTemplate.
 */
public class SlaveTemplateTest extends HudsonTestCase {

    protected void setUp() throws Exception {
        super.setUp();
        AmazonEC2Cloud.testMode = true;
    }

    protected void tearDown() throws Exception {
        super.tearDown();
        AmazonEC2Cloud.testMode = false;
    }

    public void testConfigRoundtrip() throws Exception {
        String ami = "ami1";
	String description = "foo ami";

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );

	    SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, description, "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("abc", "def", "us-east-1", "ghi", "3", templates);
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(description);
        assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,usePrivateDnsName,useEphemeralDevices");
    }

    public void testConfigRoundtripWithPrivateDns() throws Exception {
        String ami = "ami1";
	String description = "foo ami";

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, description, "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, true, null, "", false, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("abc", "def", "us-east-1", "ghi", "3", templates);
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(description);
        assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }

    /**
     * Tests to make sure the slave created has been configured properly.
     * Also tests to make sure the spot max bid price has been set properly.
     * @throws Exception - Exception that can be thrown by the Jenkins test harness
     */
    public void testConfigWithSpotBidPrice() throws Exception {
    	String ami = "ami1";
    	String description = "foo ami";

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );

        SpotConfiguration spotConfig = new SpotConfiguration(".05", SpotInstanceType.OneTime.toString());

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, spotConfig, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, "foo ami", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, true, null, "", false, "");
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("abc", "def", "us-east-1", "ghi", "3", templates);
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(description);
        assertEqualBeans(orig, received, "ami,zone,spotConfig,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }

    /**
     * Test to make sure the IAM Role is set properly.
     * 
     * @throws Exception
     */
    public void testConfigRoundtripIamRole() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, description, "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, false, null, "iamInstanceProfile", false, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("abc", "def", "us-east-1", "ghi", "3", templates);
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(description);
        assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,usePrivateDnsName,iamInstanceProfile");
    }


    public void testNullTimeoutShouldReturnMaxInt(){
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, "", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, null);
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    public void test0TimeoutShouldReturnMaxInt(){
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, "", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, "0");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    public void testNegativeTimeoutShouldReturnMaxInt(){
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, "", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, "-1");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    public void testNonNumericTimeoutShouldReturnMaxInt(){
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, "", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, "NotANumber");
        assertEquals(Integer.MAX_VALUE, st.getLaunchTimeout());
    }

    public void testBackwardCompatibleUnixData(){
        SlaveTemplate st = new SlaveTemplate("", EC2AbstractSlave.TEST_ZONE, null, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, "", "bar", "aaa", "10", "rrr", "sudo", "-Xmx1g", false, "subnet 456", null, null, false, null, "iamInstanceProfile", false, "NotANumber");
        assertFalse(st.isWindowsSlave());
        assertEquals(22, st.getSshPort());
        assertEquals("sudo", st.getRootCommandPrefix());
    }

    public void testWindowsConfigRoundTrip() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, description, "bar", "aaa", "10", "rrr", new WindowsData("password", false, ""), "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("abc", "def", "us-east-1", "ghi", "3", templates);
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(description);
        assertEqualBeans(orig, received, "amiType");
    }

    public void testUnixConfigRoundTrip() throws Exception {
        String ami = "ami1";
        String description = "foo ami";

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );

        SlaveTemplate orig = new SlaveTemplate(ami, EC2AbstractSlave.TEST_ZONE, null, "default", "foo", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, description, "bar", "aaa", "10", "rrr", new UnixData("sudo", "22"), "-Xmx1g", false, "subnet 456", tags, null, false, null, "", true, "");

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud("abc", "def", "us-east-1", "ghi", "3", templates);
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(description);
        assertEqualBeans(orig, received, "amiType");
    }
}
