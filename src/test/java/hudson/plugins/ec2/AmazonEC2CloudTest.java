package hudson.plugins.ec2;

import org.jvnet.hudson.test.HudsonTestCase;

import java.util.Collections;

/**
 * @author Kohsuke Kawaguchi
 */
public class AmazonEC2CloudTest extends HudsonTestCase {
    public void testConfigRoundtrip() throws Exception {
        AmazonEC2Cloud orig = new AmazonEC2Cloud(AwsRegion.US_EAST_1, "abc", "def", "ghi", "3", Collections.<SlaveTemplate>emptyList());
        hudson.clouds.add(orig);
        submit(createWebClient().goTo("configure").getFormByName("config"));

        assertEqualBeans(orig, hudson.clouds.iterator().next(),"region,accessId,secretKey,privateKey,instanceCap");
    }
}
