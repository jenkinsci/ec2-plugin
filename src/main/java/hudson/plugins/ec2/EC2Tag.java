package hudson.plugins.ec2;

import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.AbstractDescribableImpl;
import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.List;
import java.util.LinkedList;

import com.amazonaws.services.ec2.model.Tag;

/**
 * Created by IntelliJ IDEA.
 * User: harry
 * Date: May 22, 2012
 * Time: 9:09:01 AM
 * To change this template use File | Settings | File Templates.
 */
public class EC2Tag extends AbstractDescribableImpl<EC2Tag>
{
   private String _name;
   private String _value;

   @DataBoundConstructor
   public EC2Tag(String name, String value)
   {
      _name = name;
      _value = value;
   }


   /* Constructor from Amazon Tag */
   public EC2Tag( Tag t )
   {
       _name = t.getKey();
       _value = t.getValue();
   }


   public String getName()
   {
      return _name;
   }


   public String getValue()
   {
      return _value;
   }


   public String toString()
   {
      return "EC2Tag: " + _name + "->" + _value;
   }


   public boolean equals( Object o )
   {
      if ( o == null ) return false;
      if ( !( o instanceof EC2Tag )) return false;

      EC2Tag other = (EC2Tag) o;
      if (( _name == null && other._name != null ) || !_name.equals( other._name )) return false;
      if (( _value == null && other._value != null ) || !_value.equals( other._value )) return false;

      return true;
   }


   @Extension
   public static class DescriptorImpl extends Descriptor<EC2Tag>
   {
      public String getDisplayName() { return ""; }
   }


   /* Helper method to convert lists of Amazon tags into internal format */ 
   public static List<EC2Tag> fromAmazonTags( List<Tag> amazon_tags )
   {
       if ( null == amazon_tags )
       {
           return null;
       }

       LinkedList<EC2Tag> result = new LinkedList<EC2Tag>();
       for ( Tag t : amazon_tags )
       {
           result.add( new EC2Tag( t ));
       }

       return result;
   }
}
