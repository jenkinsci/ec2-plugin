package hudson.plugins.ec2;

import org.jvnet.hudson.test.HudsonTestCase;

import java.util.Collections;

/**
 * @author Kohsuke Kawaguchi
 */
public class AmazonEC2CloudTest extends HudsonTestCase {

	protected void setUp() throws Exception {
		super.setUp();
		AmazonEC2Cloud.testMode = true;
	}

	protected void tearDown() throws Exception {
		super.tearDown();
		AmazonEC2Cloud.testMode = false;
	}

	public void testConfigRoundtrip() throws Exception {
		AmazonEC2Cloud orig = new AmazonEC2Cloud("abc", "def", "us-east-1",
				"ghi", "3", Collections.<SlaveTemplate> emptyList(), "0.00");
		hudson.clouds.add(orig);
		submit(createWebClient().goTo("configure").getFormByName("config"));

		assertEqualBeans(orig, hudson.clouds.iterator().next(),
				"region,accessId,secretKey,privateKey,instanceCap");
	}
}
