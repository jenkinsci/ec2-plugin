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

import hudson.model.Descriptor;
import hudson.model.AbstractDescribableImpl;
import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.List;
import java.util.LinkedList;

import com.amazonaws.services.ec2.model.Tag;


public class EC2Tag extends AbstractDescribableImpl<EC2Tag>
{
   private String name;
   private String value;

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
      if (o == null) return false;
      if (!(o instanceof EC2Tag)) return false;

      EC2Tag other = (EC2Tag) o;
      if ((name == null && other.name != null) || !name.equals( other.name)) return false;
      if ((value == null && other.value != null) || !value.equals( other.value)) return false;

      return true;
   }


   @Extension
   public static class DescriptorImpl extends Descriptor<EC2Tag> {
      @Override
	  public String getDisplayName() { return ""; }
   }


   /* Helper method to convert lists of Amazon tags into internal format */ 
   public static List<EC2Tag> fromAmazonTags(List<Tag> amazon_tags) {
       if (null == amazon_tags) {
           return null;
       }

       LinkedList<EC2Tag> result = new LinkedList<EC2Tag>();
       for (Tag t : amazon_tags) {
           result.add(new EC2Tag(t));
       }

       return result;
   }
}
