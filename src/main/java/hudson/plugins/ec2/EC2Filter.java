/*
 * The MIT License
 *
 * Copyright © 2020 Endless OS Foundation LLC
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

import com.amazonaws.services.ec2.model.Filter;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.kohsuke.stapler.DataBoundConstructor;

public class EC2Filter extends AbstractDescribableImpl<EC2Filter> {
    @NonNull
    private final String name;

    /* FIXME: Ideally this would be List<String>, but Jenkins currently
     * doesn't offer a usable way to represent those in forms. Instead
     * the values are interpreted as a comma separated list.
     *
     * https://issues.jenkins.io/browse/JENKINS-27901
     */
    @NonNull
    private final String values;

    @DataBoundConstructor
    public EC2Filter(@NonNull String name, @NonNull String values) {
        this.name = Objects.requireNonNull(name);
        this.values = Objects.requireNonNull(values);
    }

    @Override
    public String toString() {
        return "EC2Filter{name=\"" + name + "\", values=\"" + values + "\"}";
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (this.getClass() != o.getClass()) {
            return false;
        }

        EC2Filter other = (EC2Filter) o;
        return name.equals(other.name) && getValuesList().equals(other.getValuesList());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, values);
    }

    @NonNull
    public String getName() {
        return name;
    }

    @NonNull
    public String getValues() {
        return values;
    }

    @NonNull
    private List<String> getValuesList() {
        return Stream.of(Util.tokenize(values)).collect(Collectors.toList());
    }

    /* Helper method to convert EC2Filter to Filter */
    @NonNull
    public Filter toFilter() {
        return new Filter(name, getValuesList());
    }

    /* Helper method to convert list of EC2Filter to list of Filter */
    @NonNull
    public static List<Filter> toFilterList(@CheckForNull List<EC2Filter> filters) {
        return Util.fixNull(filters).stream().map(EC2Filter::toFilter).collect(Collectors.toList());
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<EC2Filter> {
        @Override
        public String getDisplayName() {
            return "";
        }
    }
}
