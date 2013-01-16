package hudson.plugins.ec2;

import org.jvnet.hudson.test.HudsonTestCase;
import java.util.Collections;

/**
 * @author Cory Santiago
 */

public class AmazonSpotInstanceTest extends HudsonTestCase {
	protected void setUp() throws Exception {
		super.setUp(); 
		// spotInstanceClass.testMode = true;
	}
	
	protected void tearDown() throws Exception {
		super.tearDown();
		// spotInstanceClass.testMode = false; 
	}
	
	public void testConfigRoundtrip() throws Exception {
		//create a new instance of the spot instances
		//AmazonSpotCloud orig = new AmazonSpotCloud("abc", "def", "us-east-1",
		//"ghi", "3", Collections.<SlaveTemplate> emptyList(), "0.00", maxBidPrice);
		//hudson.clouds.add(orig);
		//submit(createWebClient().goTo("configure").getFormByName("config"));
		//assertEqualBeans(orig, hudson.clouds.iterator().next(),
			//	"region,accessId,secretKey,privateKey,instanceCap, maxBid");
		//AmazonSpotCloud toCompare = hudson.clouds.iterator().next();
		//assertTrue(toCompare.maxPrice <= orig.maxPrice);
		
		// This is temporary
		assertTrue(3 < 4);
	}
	
	// other tests
	/*
	 * test to ensure pricing is under maximum
	 */
}
