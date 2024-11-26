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

import com.amazonaws.services.ec2.model.Tag;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import org.kohsuke.stapler.DataBoundConstructor;

public class EC2Tag extends AbstractDescribableImpl<EC2Tag> {
    private final String name;
    private final String value;

    /**
     * Tag name for the specific jenkins agent type tag, used to identify the EC2 instances provisioned by this plugin.
     */
    public static final String TAG_NAME_JENKINS_SLAVE_TYPE = "jenkins_slave_type";

    public static final String TAG_NAME_JENKINS_SERVER_URL = "jenkins_server_url";

    @DataBoundConstructor
    public EC2Tag(String name, String value) {
        this.name = name;
        this.value = value;
    }

    /* Constructor from Amazon Tag */
    public EC2Tag(Tag t) {
        name = t.getKey();
        value = t.getValue();
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "EC2Tag: " + name + "->" + value;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (this.getClass() != o.getClass()) {
            return false;
        }

        EC2Tag other = (EC2Tag) o;
        if ((name == null && other.name != null) || (name != null && !name.equals(other.name))) {
            return false;
        }
        if ((value == null && other.value != null) || (value != null && !value.equals(other.value))) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<EC2Tag> {
        @Override
        public String getDisplayName() {
            return "";
        }
    }

    /* Helper method to convert lists of Amazon tags into internal format */
    public static List<EC2Tag> fromAmazonTags(List<Tag> amazonTags) {
        if (null == amazonTags) {
            return null;
        }

        LinkedList<EC2Tag> result = new LinkedList<EC2Tag>();
        for (Tag t : amazonTags) {
            result.add(new EC2Tag(t));
        }

        return result;
    }
}
