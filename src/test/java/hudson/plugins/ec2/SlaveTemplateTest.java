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

import hudson.maven.reporters.MavenMailer.DescriptorImpl;
import hudson.util.FormValidation;

import java.util.ArrayList;
import java.util.List;

import hudson.model.Node;
import org.jvnet.hudson.test.HudsonTestCase;

import com.amazonaws.http.HttpResponse;
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
        
        //SlaveTemplate orig = new SlaveTemplate(ami, null, EC2OndemandSlave.TEST_ZONE, "default", "foo", "22", InstanceType.M1Large, "ttt", "foo ami", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, false, null);

        SlaveTemplate orig = new SlaveTemplate(ami, EC2Slave.TEST_ZONE, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, description, "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, false, null);

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud( "abc", "def", "us-east-1", "ghi", "3", templates, "Test Cloud");
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(description);
        assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,usePrivateDnsName");
    }

    public void testConfigRoundtripWithPrivateDns() throws Exception {
        String ami = "ami1";
	String description = "foo ami";

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );       

        //SlaveTemplate orig = new SlaveTemplate(ami, null, EC2OndemandSlave.TEST_ZONE, "default", "foo", "22", InstanceType.M1Large, "ttt", "foo ami", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, true, null);
        SlaveTemplate orig = new SlaveTemplate(ami, EC2Slave.TEST_ZONE, "default", "foo", "22", InstanceType.M1Large, "ttt", Node.Mode.NORMAL, description, "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, true, null);

        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud( "abc", "def", "us-east-1", "ghi", "3", templates, "Test Cloud");
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(description);
        assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
    }
    
    public void testConfigWithSpotBidPrice() throws Exception {
    	String ami = "ami1";

        EC2Tag tag1 = new EC2Tag( "name1", "value1" );
        EC2Tag tag2 = new EC2Tag( "name2", "value2" );
        List<EC2Tag> tags = new ArrayList<EC2Tag>();
        tags.add( tag1 );
        tags.add( tag2 );  
        
        SpotConfiguration spotConfig = new SpotConfiguration(".05", SpotInstanceType.OneTime.name());

        SlaveTemplate orig = new SlaveTemplate(ami, spotConfig, EC2OndemandSlave.TEST_ZONE, "default", "foo", "22", InstanceType.M1Large, "ttt", "foo ami", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g", false, "subnet 456", tags, null, true, null);
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);

        AmazonEC2Cloud ac = new AmazonEC2Cloud( "abc", "def", "us-east-1", "ghi", "3", templates, "Test Cloud");
        hudson.clouds.add(ac);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(ami);
        assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts,stopOnTerminate,securityGroups,subnetId,tags,usePrivateDnsName");
        assertTrue(orig.spotConfig.spotMaxBidPrice.compareTo(received.spotConfig.spotMaxBidPrice) == 0);
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
