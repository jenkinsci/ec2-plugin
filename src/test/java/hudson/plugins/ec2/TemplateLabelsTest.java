package hudson.plugins.ec2;

import hudson.model.Label;
import hudson.model.labels.LabelAtom;

import java.util.ArrayList;
import java.util.List;

import org.jvnet.hudson.test.HudsonTestCase;

import com.xerox.amazonws.ec2.InstanceType;

public class TemplateLabelsTest extends HudsonTestCase{
	
	private AmazonEC2Cloud ac;
	private final String LABEL1 = "label1";
	private final String LABEL2 = "label2";
	
	@Override
	public void setUp() throws Exception{
		super.setUp();
		SlaveTemplate template = new SlaveTemplate("ami", "foo", "22", InstanceType.LARGE, LABEL1 + " " + LABEL2, "foo ami", "bar", "aaa", "10", "rrr", "fff", "-Xmx1g");
        List<SlaveTemplate> templates = new ArrayList<SlaveTemplate>();
        templates.add(template);
        ac = new AmazonEC2Cloud(AwsRegion.US_EAST_1, "abc", "def", "ghi", "3", templates);
	}
	
	public void testLabelAtom(){
		assertEquals(true, ac.canProvision(new LabelAtom(LABEL1)));
		assertEquals(true, ac.canProvision(new LabelAtom(LABEL2)));
		assertEquals(false, ac.canProvision(new LabelAtom("aaa")));
	}
	
	public void testLabelExpression() throws Exception{
		assertEquals(true, ac.canProvision(Label.parseExpression(LABEL1 + " || " + LABEL2)));
        assertEquals(true, ac.canProvision(Label.parseExpression(LABEL1 + " && " + LABEL2)));
        assertEquals(true, ac.canProvision(Label.parseExpression(LABEL1 + " || aaa")));
        assertEquals(false, ac.canProvision(Label.parseExpression(LABEL1 + " && aaa")));
        assertEquals(false, ac.canProvision(Label.parseExpression("aaa || bbb")));
        assertEquals(false, ac.canProvision(Label.parseExpression("aaa || bbb")));
    }

}
