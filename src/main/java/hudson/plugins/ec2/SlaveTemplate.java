package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.PrintStream;
import java.util.*;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;

/**
 * Template of {@link EC2Slave} to launch.
 *
 * @author Kohsuke Kawaguchi
 */
public class SlaveTemplate implements Describable<SlaveTemplate> {
	public final String ami;
	public final SpotConfiguration spotConfig;
	public final String description;
	public final String zone;
	public final String securityGroups;
	public final String remoteFS;
	public final String sshPort;
	public final InstanceType type;
	public final String labels;
	public final String initScript;
	public final String userData;
	public final String numExecutors;
	public final String remoteAdmin;
	public final String rootCommandPrefix;
	public final String jvmopts;
	public final String subnetId;
	public final String idleTerminationMinutes;
	public final int instanceCap;
	public final boolean stopOnTerminate;
	protected final List<EC2Tag> tags;
	public final boolean usePrivateDnsName;
	protected transient EC2Cloud parent;
	public String currentSpotPrice;


	private transient /*almost final*/ Set<LabelAtom> labelSet;
	protected transient /*almost final*/ Set<String> securityGroupSet;

	@DataBoundConstructor
	public SlaveTemplate(String ami, SpotConfiguration spotConfig, String zone, String securityGroups, String remoteFS, 
			String sshPort, InstanceType type, String labelString, String description, 
			String initScript, String userData, String numExecutors, String remoteAdmin, 
			String rootCommandPrefix, String jvmopts, boolean stopOnTerminate, String subnetId, 
			List<EC2Tag> tags, String idleTerminationMinutes, boolean usePrivateDnsName, 
			String instanceCapStr) {
		this.ami = ami;
		this.spotConfig = spotConfig;
		this.zone = zone;
		this.securityGroups = securityGroups;
		this.remoteFS = remoteFS;
		this.sshPort = sshPort;
		this.type = type;
		this.labels = Util.fixNull(labelString);
		this.description = description;
		this.initScript = initScript;
		this.userData = userData;
		this.numExecutors = Util.fixNull(numExecutors).trim();
		this.remoteAdmin = remoteAdmin;
		this.rootCommandPrefix = rootCommandPrefix;
		this.jvmopts = jvmopts;
		this.stopOnTerminate = stopOnTerminate;
		this.subnetId = subnetId;
		this.tags = tags;
		this.idleTerminationMinutes = idleTerminationMinutes;
		this.usePrivateDnsName = usePrivateDnsName;
		this.currentSpotPrice = "0.0";

		if (null == instanceCapStr || instanceCapStr.equals("")) {
			this.instanceCap = Integer.MAX_VALUE;
		} else {
			this.instanceCap = Integer.parseInt(instanceCapStr);
		}

		readResolve(); // initialize
	}

	public EC2Cloud getParent() {
		return parent;
	}

	public String getLabelString() {
		return labels;
	}

	public String getDisplayName() {
		return description+" ("+ami+")";
	}

	public String getSpotMaxBidPrice(){
		return spotConfig.spotMaxBidPrice;
	}

	String getZone() {
		return zone;
	}

	public String getSecurityGroupString() {
		return securityGroups;
	}

	public Set<String> getSecurityGroupSet() {
		return securityGroupSet;
	}

	public Set<String> parseSecurityGroups() {
		if (securityGroups == null || "".equals(securityGroups.trim())) {
			return Collections.emptySet();
		} else {
			return new HashSet<String>(Arrays.asList(securityGroups.split("\\s*,\\s*")));
		}
	}

	public int getNumExecutors() {
		try {
			return Integer.parseInt(numExecutors);
		} catch (NumberFormatException e) {
			return EC2Slave.toNumExecutors(type);
		}
	}

	public int getSshPort() {
		try {
			return Integer.parseInt(sshPort);
		} catch (NumberFormatException e) {
			return 22;
		}
	}

	public String getRemoteAdmin() {
		return remoteAdmin;
	}

	public String getRootCommandPrefix() {
		return rootCommandPrefix;
	}

	public String getSubnetId() {
		return subnetId;
	}

	public List<EC2Tag> getTags() {
		if (null == tags) return null;
		return Collections.unmodifiableList(tags);
	}

	public String getidleTerminationMinutes() {
		return idleTerminationMinutes;
	}

	public Set<LabelAtom> getLabelSet(){
		return labelSet;
	}

	public int getInstanceCap() {
		return instanceCap;
	}

	public String getInstanceCapStr() {
		if (instanceCap==Integer.MAX_VALUE) {
			return "";
		} else {
			return String.valueOf(instanceCap);
		}
	}
	
	public String getCurrentSpotPrice() {
		return currentSpotPrice;
	}

	/**
	 * Does this contain the given label?
	 *
	 * @param l
	 *      can be null to indicate "don't care".
	 */
	public boolean containsLabel(Label l) {
		return l==null || labelSet.contains(l);
	}

	public Slave provision(TaskListener listener) throws AmazonClientException, IOException{
		if (spotConfig != null && !spotConfig.spotMaxBidPrice.equals("")){
			listener.getLogger().println("Spot Price: " + spotConfig.spotMaxBidPrice);
			return provisionSpot(listener);
		}
		return provisionOndemand(listener);
	}

	

	/**
	 * Provisions a new on-demand EC2 slave.
	 *
	 * @return always non-null. This needs to be then added to {@link Hudson#addNode(Node)}.
	 */
	private EC2OndemandSlave provisionOndemand(TaskListener listener) throws AmazonClientException, IOException {
		PrintStream logger = listener.getLogger();
		AmazonEC2 ec2 = getParent().connect();

		try {
			logger.println("Launching "+ami);
			KeyPair keyPair = parent.getPrivateKey().find(ec2);
			if(keyPair==null) {
				throw new AmazonClientException("No matching keypair found on EC2. Is the EC2 private key a valid one?");
			}

			RunInstancesRequest riRequest = new RunInstancesRequest(ami, 1, 1);

			List<Filter> diFilters = new ArrayList<Filter>();
			diFilters.add(new Filter("image-id").withValues(ami));

			if (StringUtils.isNotBlank(getZone())) {
				Placement placement = new Placement(getZone());
				riRequest.setPlacement(placement);
				diFilters.add(new Filter("availability-zone").withValues(getZone()));
			}

			if (StringUtils.isNotBlank(getSubnetId())) {
				riRequest.setSubnetId(getSubnetId());
				diFilters.add(new Filter("subnet-id").withValues(getSubnetId()));

				/* If we have a subnet ID then we can only use VPC security groups */
				if (!securityGroupSet.isEmpty()) {
					List<String> group_ids = new ArrayList<String>();

					DescribeSecurityGroupsRequest group_req = new DescribeSecurityGroupsRequest();
					group_req.withFilters(new Filter("group-name").withValues(securityGroupSet));
					DescribeSecurityGroupsResult group_result = ec2.describeSecurityGroups(group_req);

					for (SecurityGroup group : group_result.getSecurityGroups()) {
						if (group.getVpcId() != null && !group.getVpcId().isEmpty()) {
							List<Filter> filters = new ArrayList<Filter>();
							filters.add(new Filter("vpc-id").withValues(group.getVpcId()));
							filters.add(new Filter("state").withValues("available"));
							filters.add(new Filter("subnet-id").withValues(getSubnetId()));

							DescribeSubnetsRequest subnet_req = new DescribeSubnetsRequest();
							subnet_req.withFilters(filters);
							DescribeSubnetsResult subnet_result = ec2.describeSubnets(subnet_req);

							List<Subnet> subnets = subnet_result.getSubnets();
							if(subnets != null && !subnets.isEmpty()) {
								group_ids.add(group.getGroupId());
							}
						}
					}

					if (securityGroupSet.size() != group_ids.size()) {
						throw new AmazonClientException( "Security groups must all be VPC security groups to work in a VPC context" );
					}

					if (!group_ids.isEmpty()) {
						riRequest.setSecurityGroupIds(group_ids);
						diFilters.add(new Filter("instance.group-id").withValues(group_ids));
					}
				}
			} else {
				/* No subnet: we can use standard security groups by name */
				riRequest.setSecurityGroups(securityGroupSet);
				if (securityGroupSet.size() > 0)
					diFilters.add(new Filter("group-name").withValues(securityGroupSet));
			}

			String userDataString = Base64.encodeBase64String(userData.getBytes());
			riRequest.setUserData(userDataString);
			riRequest.setKeyName(keyPair.getKeyName());
			diFilters.add(new Filter("key-name").withValues(keyPair.getKeyName()));
			riRequest.setInstanceType(type.toString());
			diFilters.add(new Filter("instance-type").withValues(type.toString()));

			HashSet<Tag> inst_tags = null;
			if (tags != null && !tags.isEmpty()) {
				inst_tags = new HashSet<Tag>();
				for(EC2Tag t : tags) {
					diFilters.add(new Filter("tag:"+t.getName()).withValues(t.getValue()));
				}
			}

			DescribeInstancesRequest diRequest = new DescribeInstancesRequest();
			diFilters.add(new Filter("instance-state-name").withValues(InstanceStateName.Stopped.toString(), 
					InstanceStateName.Stopping.toString()));
			diRequest.setFilters(diFilters);
			logger.println("Looking for existing instances: "+diRequest);

			DescribeInstancesResult diResult = ec2.describeInstances(diRequest);
			if (diResult.getReservations().size() == 0) {
				// Have to create a new instance
				Instance inst = ec2.runInstances(riRequest).getReservation().getInstances().get(0);

				/* Now that we have our instance, we can set tags on it */
				if (inst_tags != null) {
					CreateTagsRequest tag_request = new CreateTagsRequest();
					tag_request.withResources(inst.getInstanceId()).setTags(inst_tags);
					ec2.createTags(tag_request);

					// That was a remote request - we should also update our local instance data.
					inst.setTags(inst_tags);
				}
				logger.println("No existing instance found - created: "+inst);
				return newOndemandSlave(inst);
			}

			Instance inst = diResult.getReservations().get(0).getInstances().get(0);
			logger.println("Found existing stopped instance: "+inst);
			List<String> instances = new ArrayList<String>();
			instances.add(inst.getInstanceId());
			StartInstancesRequest siRequest = new StartInstancesRequest(instances);
			StartInstancesResult siResult = ec2.startInstances(siRequest);
			logger.println("Starting existing instance: "+inst+ " result:"+siResult);

			List<Node> nodes = Hudson.getInstance().getNodes();
			for (int i = 0, len = nodes.size(); i < len; i++) {
				if (!(nodes.get(i) instanceof EC2OndemandSlave))
					continue;
				EC2OndemandSlave ec2Node = (EC2OndemandSlave) nodes.get(i);
				if (ec2Node.getInstanceId().equals(inst.getInstanceId())) {
					logger.println("Found existing corresponding: "+ec2Node);
					return ec2Node;
				}
			}

			// Existing slave not found 
			logger.println("Creating new slave for existing instance: "+inst);
			return newOndemandSlave(inst);

		} catch (FormException e) {
			throw new AssertionError(); // we should have discovered all configuration issues upfront
		}
	}

	/**
	 * Provision a new slave for an EC2 spot instance to call back to
	 * 
	 * @return always non-null. This needs to be then added to {@link Hudson#addNode(Node)}.
	 */
	private EC2Slave provisionSpot(TaskListener listener) throws AmazonClientException, IOException {
		PrintStream logger = listener.getLogger();
		AmazonEC2 ec2 = getParent().connect();

		try{
			logger.println("Launching "+ami);
			KeyPair keyPair = parent.getPrivateKey().find(ec2);
			if(keyPair==null) {
				throw new AmazonClientException("No matching keypair found on EC2. Is the EC2 private key a valid one?");
			}

			RequestSpotInstancesRequest spotRequest = new RequestSpotInstancesRequest();

			spotRequest.setSpotPrice(spotConfig.spotMaxBidPrice);
			spotRequest.setInstanceCount(Integer.valueOf(1));

			LaunchSpecification launchSpecification = new LaunchSpecification();

			List<Filter> diFilters = new ArrayList<Filter>();

			launchSpecification.setImageId(ami);
			launchSpecification.setInstanceType(type);
			diFilters.add(new Filter("image-id").withValues(ami));
			
			if (StringUtils.isNotBlank(getZone())) {
				SpotPlacement placement = new SpotPlacement(getZone());
				launchSpecification.setPlacement(placement);
				diFilters.add(new Filter("availability-zone").withValues(getZone()));

			}

			if (StringUtils.isNotBlank(getSubnetId())) {
				launchSpecification.setSubnetId(getSubnetId());
				diFilters.add(new Filter("subnet-id").withValues(getSubnetId()));

				/* If we have a subnet ID then we can only use VPC security groups */
				if (!securityGroupSet.isEmpty()) {
					List<String> group_ids = new ArrayList<String>();

					DescribeSecurityGroupsRequest group_req = new DescribeSecurityGroupsRequest();
					group_req.withFilters(new Filter("group-name").withValues(securityGroupSet));
					DescribeSecurityGroupsResult group_result = ec2.describeSecurityGroups(group_req);

					for (SecurityGroup group : group_result.getSecurityGroups()) {
						if (group.getVpcId() != null && !group.getVpcId().isEmpty()) {
							List<Filter> filters = new ArrayList<Filter>();
							filters.add(new Filter("vpc-id").withValues(group.getVpcId()));
							filters.add(new Filter("state").withValues("available"));
							filters.add(new Filter("subnet-id").withValues(getSubnetId()));

							DescribeSubnetsRequest subnet_req = new DescribeSubnetsRequest();
							subnet_req.withFilters(filters);
							DescribeSubnetsResult subnet_result = ec2.describeSubnets(subnet_req);

							List<Subnet> subnets = subnet_result.getSubnets();
							if(subnets != null && !subnets.isEmpty()) {
								group_ids.add(group.getGroupId());
							}
						}
					}

					if (securityGroupSet.size() != group_ids.size()) {
						throw new AmazonClientException( "Security groups must all be VPC security groups to work in a VPC context" );
					}

					if (!group_ids.isEmpty()) {
						launchSpecification.setSecurityGroups(group_ids);
						diFilters.add(new Filter("instance.group-id").withValues(group_ids));
					}
				}
			} else {
				/* No subnet: we can use standard security groups by name */
				launchSpecification.setSecurityGroups(securityGroupSet);
				if (securityGroupSet.size() > 0)
					diFilters.add(new Filter("group-name").withValues(securityGroupSet));
			}

			String jenkinsUrl = Jenkins.getInstance().getRootUrl();
			String slaveName = UUID.randomUUID().toString();
			String newUserData = "JENKINS_URL=" + jenkinsUrl + "&SLAVE_NAME=" + slaveName + "&" + userData;

			String userDataString = Base64.encodeBase64String(newUserData.getBytes());
			launchSpecification.setUserData(userDataString);
			launchSpecification.setKeyName(keyPair.getKeyName());
			diFilters.add(new Filter("key-name").withValues(keyPair.getKeyName()));
			launchSpecification.setInstanceType(type.toString());
			diFilters.add(new Filter("instance-type").withValues(type.toString()));

			HashSet<Tag> inst_tags = null;
			if (tags != null && !tags.isEmpty()) {
				inst_tags = new HashSet<Tag>();
				for(EC2Tag t : tags) {
					diFilters.add(new Filter("tag:"+t.getName()).withValues(t.getValue()));
				}
			}

			diFilters.add(new Filter("instance-state-name").withValues(InstanceStateName.Stopped.toString(), 
					InstanceStateName.Stopping.toString()));

			spotRequest.setLaunchSpecification(launchSpecification);

			// Have to create a new instance
			RequestSpotInstancesResult reqResult = ec2.requestSpotInstances(spotRequest);
			SpotInstanceRequest spotInstReq = null;

			List<SpotInstanceRequest> reqInstances = reqResult.getSpotInstanceRequests();
			if (reqInstances.size() <= 0){
				throw new AmazonClientException("No spot instances found");
			}

			spotInstReq = reqInstances.get(0);
			if (spotInstReq != null && spotInstReq.getSpotInstanceRequestId() != null){
				System.out.println("Spot instance id in provision: " + spotInstReq.getSpotInstanceRequestId());
			}
			
			return newSpotSlave(spotInstReq, slaveName);

		}  catch (FormException e) {
			throw new AssertionError(); // we should have discovered all configuration issues upfront
		}
	}


	private EC2SpotSlave newSpotSlave(SpotInstanceRequest sir, String name) throws FormException, IOException {
		return new EC2SpotSlave(name, sir.getSpotInstanceRequestId(), description, remoteFS,
				getNumExecutors(), labels, remoteAdmin, rootCommandPrefix, jvmopts,
				stopOnTerminate, idleTerminationMinutes, EC2Tag.fromAmazonTags(sir.getTags()));
	}
	
	private EC2OndemandSlave newOndemandSlave(Instance inst) throws FormException, IOException {
		return new EC2OndemandSlave(inst.getInstanceId(), inst.getInstanceId(), description, remoteFS, getSshPort(), 
				getNumExecutors(), labels, initScript, remoteAdmin, rootCommandPrefix, 
				jvmopts, stopOnTerminate, idleTerminationMinutes, 
				inst.getPublicDnsName(), inst.getPrivateDnsName(), 
				EC2Tag.fromAmazonTags(inst.getTags()), usePrivateDnsName);
	}
	
	/**
	 * Provisions a new EC2 slave based on the currently running instance on EC2,
	 * instead of starting a new one.
	 */
	public EC2OndemandSlave attach(String instanceId, TaskListener listener) throws AmazonClientException, IOException {
		PrintStream logger = listener.getLogger();
		AmazonEC2 ec2 = getParent().connect();

		try {
			logger.println("Attaching to "+instanceId);
			DescribeInstancesRequest request = new DescribeInstancesRequest();
			request.setInstanceIds(Collections.singletonList(instanceId));
			Instance inst = ec2.describeInstances(request).getReservations().get(0).getInstances().get(0);
			return newOndemandSlave(inst);
		} catch (FormException e) {
			throw new AssertionError(); // we should have discovered all configuration issues upfront
		}
	}

	/**
	 * Initializes data structure that we don't persist.
	 */
	protected Object readResolve() {
		labelSet = Label.parse(labels);
		securityGroupSet = parseSecurityGroups();
		return this;
	}

	public Descriptor<SlaveTemplate> getDescriptor() {
		return Hudson.getInstance().getDescriptor(getClass());
	}
	
	@Extension
	public static final class DescriptorImpl extends Descriptor<SlaveTemplate> {
		@Override
		public String getDisplayName() {
			return null;
		}

		/**
		 * Since this shares much of the configuration with {@link EC2Computer}, check its help page, too.
		 */
		@Override
		public String getHelpFile(String fieldName) {
			String p = super.getHelpFile(fieldName);
			// TODO make this generic for all of EC2. Not just on demand
			if (p==null)        p = Hudson.getInstance().getDescriptor(EC2OndemandSlave.class).getHelpFile(fieldName);
			return p;
		}

		/***
		 * Check that the AMI requested is available in the cloud and can be used.
		 */
		public FormValidation doValidateAmi(
				@QueryParameter String accessId, @QueryParameter String secretKey,
				@QueryParameter String region,
				final @QueryParameter String ami) throws IOException, ServletException {
			AmazonEC2 ec2 = EC2Cloud.connect(accessId, secretKey, AmazonEC2Cloud.getEc2EndpointUrl(region));
			if(ec2!=null) {
				try {
					List<String> images = new LinkedList<String>();
					images.add(ami);
					List<String> owners = new LinkedList<String>();
					List<String> users = new LinkedList<String>();
					DescribeImagesRequest request = new DescribeImagesRequest();
					request.setImageIds(images);
					request.setOwners(owners);
					request.setExecutableUsers(users);
					List<Image> img = ec2.describeImages(request).getImages();
					if(img==null || img.isEmpty())
						// de-registered AMI causes an empty list to be returned. so be defensive
						// against other possibilities
						return FormValidation.error("No such AMI, or not usable with this accessId: "+ami);
					return FormValidation.ok(img.get(0).getImageLocation()+" by "+img.get(0).getImageOwnerAlias());
				} catch (AmazonClientException e) {
					return FormValidation.error(e.getMessage());
				}
			} else
				return FormValidation.ok();   // can't test
		}

		public FormValidation doCheckIdleTerminationMinutes(@QueryParameter String value) {
			if (value == null || value.trim() == "") return FormValidation.ok();
			try {
				int val = Integer.parseInt(value);
				if (val >= 0) return FormValidation.ok();
			}
			catch ( NumberFormatException nfe ) {}
			return FormValidation.error("Idle Termination time must be a non-negative integer (or null)");
		}

		public FormValidation doCheckInstanceCapStr(@QueryParameter String value) {
			if (value == null || value.trim() == "") return FormValidation.ok();
			try {
				int val = Integer.parseInt(value);
				if (val >= 0) return FormValidation.ok();
			} catch ( NumberFormatException nfe ) {}
			return FormValidation.error("InstanceCap must be a non-negative integer (or null)");
		}

		public ListBoxModel doFillZoneItems( @QueryParameter String accessId,
				@QueryParameter String secretKey,
				@QueryParameter String region)
						throws IOException, ServletException
						{
			return EC2Slave.fillZoneItems(accessId, secretKey, region);
						}
		
		/* Validate the Spot Max Bid Price to ensure that it is a floating point number*/
		public FormValidation doCheckSpotMaxBidPrice( @QueryParameter String spotMaxBidPrice ) {
			try {
				float spotPrice = Float.parseFloat(spotMaxBidPrice);

				/* If the specified bid price is less than 0.001 return an error*/
				if(spotPrice < 0.001){
					return FormValidation.error("Not a correct bid price");
				} else {
					return FormValidation.ok();
				}
			} catch (NumberFormatException ex) {
				return FormValidation.error("Not a correct bid price");
			} 
		}
		
		/* Check the current Spot price of the selected instance type for the selected region */
		public FormValidation doCurrentSpotPrice( @QueryParameter String accessId, @QueryParameter String secretKey,
				@QueryParameter String region, @QueryParameter InstanceType type, 
				@QueryParameter String zone ) throws IOException, ServletException {
			String cp = "";
			// Connect to the EC2 cloud with the access id, secret key, and region queried from the created cloud
			AmazonEC2 ec2 = EC2Cloud.connect(accessId, secretKey, AmazonEC2Cloud.getEc2EndpointUrl(region));
			if(ec2!=null) {

				try {
					// Build a new price history request with the currently selected type
					DescribeSpotPriceHistoryRequest request = new DescribeSpotPriceHistoryRequest();
					
					// If a zone is specified, set the availability zone in the request
					// Else, proceed with no availability zone which will result with the cheapest Spot price
					if(zone != ""){
						request.setAvailabilityZone(zone);
					}
					
					Collection<String> instanceType = new ArrayList<String>();
					instanceType.add(type.toString());
					request.setInstanceTypes(instanceType);
					
					// Retrieve the price history request result and store the current price
					DescribeSpotPriceHistoryResult result = ec2.describeSpotPriceHistory(request);
					SpotPrice currentPrice = result.getSpotPriceHistory().get(0);

					cp = currentPrice.getSpotPrice();
				} catch (AmazonClientException e) {
					return FormValidation.error("Could not retrieve current Spot price");
				}
			}
			
			/*
			 * If we could not return the current price of the instance display an error
			 * Else, remove the additional zeros from the current price and return it to the interface
			 * in the form of a message
			 */
			if(cp == ""){
				return FormValidation.error("Could not retrieve current Spot price");
			} else {
				cp = cp.substring(0, cp.length() - 3);
				
				return FormValidation.ok("The current Spot price for a " + type.toString() + 
						" in the " + region + " region is $" + cp );
			}
		}
	}
}

