package hudson.plugins.ec2.hughtest;

import static org.mockito.Mockito.when;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.plugins.ec2.EC2PrivateKey;
import hudson.plugins.ec2.Messages;
import hudson.plugins.ec2.AmazonEC2Cloud;
import hudson.slaves.Cloud;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.net.URL;
import java.util.*;

import javax.servlet.ServletException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.mockito.Mockito;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.KeyPair;

public class AmazonEC2CloudTester extends AmazonEC2Cloud
{
	static MockAmazonEC2 mockAmazonEC2;

    @DataBoundConstructor
    public AmazonEC2CloudTester(String accessId, String secretKey, String region, String privateKey, String instanceCapStr, 
    		List<SlaveTemplateForTests> templates) {
        super("mockec2cloud_" + region, accessId, secretKey, privateKey,
         instanceCapStr, templates);
    }
	
	@Override
    public synchronized AmazonEC2 connect() throws AmazonClientException {
		if( connection == null ) {
			mockAmazonEC2 = new MockAmazonEC2();
			connection = mockAmazonEC2;
	    	mockAmazonEC2.setKeyName("testkeypair");
	    	try {
				mockAmazonEC2.setFingerprint(privateKey.getFingerprint());
			} catch (IOException e) {
				e.printStackTrace();
			}
	    	mockAmazonEC2.setPrivateKey(privateKey.getPrivateKey());
		}
		return connection;
	}
	
	@Extension
	public static class DescriptorImpl extends Descriptor<Cloud> {

		@Override
		public String getDisplayName() {
			return "AmazonEC2CloudTester";
		}
		
		public ListBoxModel doFillBidTypeItems( @QueryParameter String accessId,
				@QueryParameter String secretKey, @QueryParameter String region) throws IOException,
				ServletException {
			ListBoxModel model = new ListBoxModel();
			return model;
		}
		
		public ListBoxModel doFillRegionItems(@QueryParameter String accessId,
				@QueryParameter String secretKey, @QueryParameter String region) throws IOException,
				ServletException {
			ListBoxModel model = new ListBoxModel();
			model.add(AmazonEC2Cloud.DEFAULT_EC2_HOST);
			return model;
		}

        public FormValidation doTestConnection( URL ec2endpoint,
                String accessId, String secretKey, String privateKey) throws IOException, ServletException {
            return FormValidation.ok(Messages.EC2Cloud_Success());
        }
	}
}
