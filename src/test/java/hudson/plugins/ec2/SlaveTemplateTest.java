package hudson.plugins.ec2;

import hudson.maven.reporters.MavenMailer.DescriptorImpl;
import hudson.util.FormValidation;

import java.util.ArrayList;
import java.util.List;

import org.jvnet.hudson.test.HudsonTestCase;

import com.amazonaws.http.HttpResponse;
import com.amazonaws.services.ec2.model.InstanceType;

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

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );

        SlaveTemplate orig = new SlaveTemplate(ami, "",EC2Slave.TEST_ZONE, "default", "foo", "22", InstanceType.M1Large, "ttt", "foo ami", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, false, null);

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud( "abc", "def", "us-east-1", "ghi", "3", templates);
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(ami);
        assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,usePrivateDnsName");
    }

    public void testConfigRoundtripWithPrivateDns() throws Exception {
        String ami = "ami1";

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );

        SlaveTemplate orig = new SlaveTemplate(ami, "",EC2Slave.TEST_ZONE, "default", "foo", "22", InstanceType.M1Large, "ttt", "foo ami", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, true, null);

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud( "abc", "def", "us-east-1", "ghi", "3", templates);
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(ami);
        assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }
    
    // Test to ensure doCheckSpotMaxBidPrice is properly validating 
    public void testMaxBidPriceValidator() throws Exception {
    	String[] validSpotBidPrices = {"0.003", Float.toString(Float.MAX_VALUE), "3.000011111", "0.001"}; 
    	String[] invalidSpotBidPrice = {Float.toString(-Float.MIN_VALUE), "-1.0", "xer"};
    	
    	hudson.plugins.ec2.SlaveTemplate.DescriptorImpl toTest = new hudson.plugins.ec2.SlaveTemplate.DescriptorImpl();
    	for(int i=0; i < validSpotBidPrices.length; i++){    		
    		assertEquals(FormValidation.ok(), toTest.doCheckSpotMaxBidPrice(validSpotBidPrices[i]));
    	}
    	for(int i=0; i < invalidSpotBidPrice.length; i++){
    		assertNotSame(FormValidation.ok(), toTest.doCheckSpotMaxBidPrice(invalidSpotBidPrice[i]));
    	}
    }
}
