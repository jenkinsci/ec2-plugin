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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import software.amazon.awssdk.services.ec2.model.InstanceType;

public class TemplateLabelsTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private EC2Cloud ac;
    private final String LABEL1 = "label1";
    private final String LABEL2 = "label2";

    private void setUpCloud(String label) throws Exception {
        setUpCloud(label, Node.Mode.NORMAL);
    }

    private void setUpCloud(String label, Node.Mode mode) throws Exception {
        EC2Tag tag1 = new EC2Tag("name1", "value1");
        EC2Tag tag2 = new EC2Tag("name2", "value2");
        List<EC2Tag> tags = new ArrayList<>();
        tags.add(tag1);
        tags.add(tag2);

        SlaveTemplate template = new SlaveTemplate(
                "ami",
                "foo",
                null,
                "default",
                "zone",
                InstanceType.M1_LARGE.toString(),
                false,
                label,
                mode,
                "foo ami",
                "bar",
                "bbb",
                "aaa",
                "10",
                "fff",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "-Xmx1g",
                true,
                "subnet 456",
                tags,
                null,
                0,
                0,
                null,
                "",
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
        List<SlaveTemplate> templates = new ArrayList<>();
        templates.add(template);

        ac = new EC2Cloud("us-east-1", false, "abc", "us-east-1", "ghi", null, "3", templates, null, null);
    }

    @Test
    public void testLabelAtom() throws Exception {
        setUpCloud(LABEL1 + " " + LABEL2);

        assertTrue(ac.canProvision(new LabelAtom(LABEL1)));
        assertTrue(ac.canProvision(new LabelAtom(LABEL2)));
        assertFalse(ac.canProvision(new LabelAtom("aaa")));
        assertTrue(ac.canProvision((Label) null));
    }

    @Test
    public void testLabelExpression() throws Exception {
        setUpCloud(LABEL1 + " " + LABEL2);

        assertTrue(ac.canProvision(Label.parseExpression(LABEL1 + " || " + LABEL2)));
        assertTrue(ac.canProvision(Label.parseExpression(LABEL1 + " && " + LABEL2)));
        assertTrue(ac.canProvision(Label.parseExpression(LABEL1 + " || aaa")));
        assertFalse(ac.canProvision(Label.parseExpression(LABEL1 + " && aaa")));
        assertFalse(ac.canProvision(Label.parseExpression("aaa || bbb")));
        assertFalse(ac.canProvision(Label.parseExpression("aaa || bbb")));
    }

    @Test
    public void testEmptyLabel() throws Exception {
        setUpCloud("");

        assertTrue(ac.canProvision((Label) null));
    }

    @Test
    public void testExclusiveMode() throws Exception {
        setUpCloud(LABEL1 + " " + LABEL2, Node.Mode.EXCLUSIVE);

        assertTrue(ac.canProvision(new LabelAtom(LABEL1)));
        assertTrue(ac.canProvision(new LabelAtom(LABEL2)));
        assertFalse(ac.canProvision(new LabelAtom("aaa")));
        assertFalse(ac.canProvision((Label) null));
    }

    @Test
    public void testExclusiveModeEmptyLabel() throws Exception {
        setUpCloud("", Node.Mode.EXCLUSIVE);

        assertFalse(ac.canProvision((Label) null));
    }
}
