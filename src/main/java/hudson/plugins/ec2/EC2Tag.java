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


   public String toString() {
      return "EC2Tag: " + name + "->" + value;
   }


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
