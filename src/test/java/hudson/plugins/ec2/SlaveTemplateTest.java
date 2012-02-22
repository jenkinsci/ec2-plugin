package hudson.plugins.ec2;

import java.util.ArrayList;
import java.util.List;

import org.jvnet.hudson.test.HudsonTestCase;

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
        SlaveTemplate orig = new SlaveTemplate(ami, EC2Slave.TEST_ZONE, "foo", "22", InstanceType.M1Large, "ttt", "foo ami", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g");
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);
        AmazonEC2Cloud ac = new AmazonEC2Cloud( "abc", "def", "us-east-1", "ghi", "3", templates);
        hudson.clouds.add(ac);
        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(ami);
        assertEqualBeans(orig, received, "ami,zone,description,remoteFS,type,jvmopts");
    }
}
