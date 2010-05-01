package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.InstanceType;
import org.jvnet.hudson.test.HudsonTestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * Basic test to validate SlaveTemplate.
 */
public class SlaveTemplateTest extends HudsonTestCase {

    public void testConfigRoundtrip() throws Exception {
        String ami = "ami1";
        SlaveTemplate orig = new SlaveTemplate(ami, "foo", InstanceType.LARGE, "ttt", "foo ami", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g");
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(orig);
        AmazonEC2Cloud ac = new AmazonEC2Cloud(AwsRegion.US_EAST_1, "abc", "def", "ghi", "3", templates);
        hudson.clouds.add(ac);
        submit(createWebClient().goTo("configure").getFormByName("config"));
        SlaveTemplate received = ((EC2Cloud)hudson.clouds.iterator().next()).getTemplate(ami);
        assertEqualBeans(orig, received, "ami,description,remoteFS,type,jvmopts");
    }
}
