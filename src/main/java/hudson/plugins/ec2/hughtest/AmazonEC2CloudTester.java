package hudson.plugins.ec2.hughtest;

import static org.mockito.Mockito.when;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.plugins.ec2.EC2PrivateKey;
import hudson.plugins.ec2.Messages;
import hudson.plugins.ec2.AmazonEC2Cloud;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
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

public class AmazonEC2CloudTester 
extends AmazonEC2Cloud
//extends Cloud
{
	static MockAmazonEC2 mockAmazonEC2;
//	AmazonEC2Cloud amazonEC2Cloud;

//  @DataBoundConstructor
//  public MockEC2Cloud() {
//	  super("mymockec2cloud");
//	  super("mockec2cloud", "myaccessid", "mysecretkey", "myprivatekey",
//			"1",new ArrayList<SlaveTemplate>()  );
//  }

    @DataBoundConstructor
    public AmazonEC2CloudTester(String accessId, String secretKey, String region, String privateKey, String instanceCapStr, 
    		List<SlaveTemplateForTests> templates) {
//    	super("mockec2cloud_" + region);
//    	amazonEC2Cloud = new AmazonEC2Cloud(accessId, secretKey, region, privateKey, instanceCapStr, templates);
        super("mockec2cloud_" + region, accessId, secretKey, privateKey,
         instanceCapStr, templates);
    	System.out.println("MockEC2Cloud " + region );
//        this.region = region;
    }
	
	@Override
	public EC2PrivateKey getPrivateKey() {
		EC2PrivateKey mockPrivateKey = Mockito.mock(EC2PrivateKey.class);
		KeyPair keyPair = new KeyPair();
		keyPair.setKeyFingerprint("mymockfingerprint");
		keyPair.setKeyName(mockAmazonEC2.getKeyname());
		keyPair.setKeyMaterial(mockAmazonEC2.getPrivateKey());
		try {
			when(mockPrivateKey.find(Mockito.any(AmazonEC2.class))).thenReturn(keyPair);
		} catch (AmazonClientException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return mockPrivateKey;
	}

//	@Override
//	public boolean canProvision(Label label) {
//		System.out.println("canProvision " + label.getName() );
//		return this.getTemplate(label) != null;
//	}
//
//	@Override
//	public Collection<PlannedNode> provision(Label label, int excessWorkload) {
//		System.out.println("provision " + label.getName() + " excess workload " + excessWorkload );
//		ArrayList<PlannedNode> plannedNodes = new ArrayList<PlannedNode>();
//		return plannedNodes;
//	}

	@Override
    public synchronized AmazonEC2 connect() throws AmazonClientException {
    	System.out.println("MockEC2Cloud.connect()");
		if( connection == null ) {
			mockAmazonEC2 = new MockAmazonEC2();
			connection = mockAmazonEC2;
	    	mockAmazonEC2.setKeyName("testkeypair");
	    	try {
				mockAmazonEC2.setFingerprint(privateKey.getFingerprint());
			} catch (IOException e) {
				// TODO Auto-generated catch block
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
			System.out.println("doFillBidTypeItems");
			ListBoxModel model = new ListBoxModel();
//			model.add(AmazonEC2Cloud.DEFAULT_EC2_HOST);
			return model;
		}
		
		public ListBoxModel doFillRegionItems(@QueryParameter String accessId,
				@QueryParameter String secretKey, @QueryParameter String region) throws IOException,
				ServletException {
			System.out.println("doFillRegionItems");
			ListBoxModel model = new ListBoxModel();
			model.add(AmazonEC2Cloud.DEFAULT_EC2_HOST);
			return model;
		}

        public FormValidation doTestConnection( URL ec2endpoint,
                String accessId, String secretKey, String privateKey) throws IOException, ServletException {
        	System.out.println("AmazonEC2CloudTester.DescriptorImpl.doTestConnection");
            return FormValidation.ok(Messages.EC2Cloud_Success());
        }
	}
}
