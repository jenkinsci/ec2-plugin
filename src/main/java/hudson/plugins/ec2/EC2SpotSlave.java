package hudson.plugins.ec2;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Slave.SlaveDescriptor;
import hudson.model.Node;
import hudson.plugins.ec2.ssh.EC2UnixLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.util.ListBoxModel;

public class EC2SpotSlave extends EC2Slave {
	
	public EC2SpotSlave(String instanceId, String description, String remoteFS,
			int numExecutors, String labelString, String remoteAdmin, 
			String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, 
			String idleTerminationMinutes, List<EC2Tag> tags) 
					throws FormException, IOException {
		
		this(instanceId, description, remoteFS, numExecutors, Mode.NORMAL, labelString, new EC2UnixLauncher(), 
				new EC2RetentionStrategy(idleTerminationMinutes), Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, stopOnTerminate,
				idleTerminationMinutes, tags);
	}

	public EC2SpotSlave(String instanceId, String description, String remoteFS,
			int numExecutors, Mode mode, String labelString,
			ComputerLauncher launcher, EC2RetentionStrategy retentionStrategy,
			List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin,
					String rootCommandPrefix, String jvmopts, boolean stopOnTerminate,
					String idleTerminationMinutes, List<EC2Tag> tags)
							throws FormException, IOException {
		
		super(instanceId, description, remoteFS, numExecutors, mode, labelString,
				launcher, retentionStrategy, nodeProperties, remoteAdmin,
				rootCommandPrefix, jvmopts, stopOnTerminate, idleTerminationMinutes,
				tags);
		// TODO Auto-generated constructor stub
	}

	@Override
	public Computer createComputer() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void terminate() {
		// TODO Auto-generated method stub

	}

	@Override
	void idleTimeout() {
		// TODO Auto-generated method stub

	}

	@Override
	protected void fetchLiveInstanceData(boolean force) {
		// TODO Auto-generated method stub

	}

	@Override
	public Node reconfigure(StaplerRequest req, JSONObject form)
			throws FormException {
		// TODO Auto-generated method stub
		return null;
	}
	
	public static ListBoxModel fillZoneItems(String accessId, String secretKey,
			String region) throws IOException, ServletException {
		// TODO Copied from EC2OndemandSlave
		return null;
	}

	@Extension
	public static final class DescriptorImpl extends SlaveDescriptor {
		@Override
		public String getDisplayName() {
			return "Amazon EC2";
		}

		@Override
		public boolean isInstantiable() {
			return false;
		}

		public ListBoxModel doFillZoneItems(@QueryParameter String accessId,
				@QueryParameter String secretKey, @QueryParameter String region) throws IOException,
				ServletException {
			return fillZoneItems(accessId, secretKey, region);
		}
	}

	private static final Logger LOGGER = Logger.getLogger(EC2SpotSlave.class.getName());


}
