package hudson.plugins.ec2;

import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.DescribeSubnetsRequest;
import com.amazonaws.services.ec2.model.DescribeSubnetsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.LaunchSpecification;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotPlacement;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesResult;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Tag;

public class SpotSlaveTemplate extends SlaveTemplate {

	private float bidPrice;

	public SpotSlaveTemplate(String ami, String zone, String securityGroups,
			String remoteFS, String sshPort, InstanceType type,
			String labelString, String description, String initScript,
			String userData, String numExecutors, String remoteAdmin,
			String rootCommandPrefix, String jvmopts, boolean stopOnTerminate,
			String subnetId, List<EC2Tag> tags, String idleTerminationMinutes,
			boolean usePrivateDnsName, String instanceCapStr, Float bidPrice) {

		super(ami, zone, securityGroups, remoteFS, sshPort, type, labelString,
				description, initScript, userData, numExecutors, remoteAdmin,
				rootCommandPrefix, jvmopts, stopOnTerminate, subnetId, tags,
				idleTerminationMinutes, usePrivateDnsName, instanceCapStr);

		this.bidPrice = bidPrice;
	}

	@Override
	public EC2Slave provision(TaskListener listener) throws AmazonClientException, IOException {
		PrintStream logger = listener.getLogger();
		AmazonEC2 ec2 = getParent().connect();

		try{
			logger.println("Launching "+ami);
			KeyPair keyPair = parent.getPrivateKey().find(ec2);
			if(keyPair==null) {
				throw new AmazonClientException("No matching keypair found on EC2. Is the EC2 private key a valid one?");
			}

			RequestSpotInstancesRequest spotRequest = 
					new RequestSpotInstancesRequest(Float.toString(this.bidPrice));

			List<Filter> diFilters = new ArrayList<Filter>();
			diFilters.add(new Filter("image-id").withValues(ami));

			LaunchSpecification launchSpecification = new LaunchSpecification();
			spotRequest.setLaunchSpecification(launchSpecification);

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

			String userDataString = Base64.encodeBase64String(userData.getBytes());
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

			DescribeSpotInstanceRequestsRequest dsirRequest = new DescribeSpotInstanceRequestsRequest();
			diFilters.add(new Filter("instance-state-name").withValues(InstanceStateName.Stopped.toString(), 
					InstanceStateName.Stopping.toString()));
			dsirRequest.setFilters(diFilters);
			logger.println("Looking for existing instances: "+dsirRequest);

			DescribeSpotInstanceRequestsResult dsirResult = ec2.describeSpotInstanceRequests(dsirRequest);
			if (dsirResult.getSpotInstanceRequests().size() == 0) {
				// Have to create a new instance
				SpotInstanceRequest inst = ec2.requestSpotInstances(spotRequest).getSpotInstanceRequests().get(0);

				/* Now that we have our instance, we can set tags on it */
				if (inst_tags != null) {
					CreateTagsRequest tag_request = new CreateTagsRequest();
					tag_request.withResources(inst.getInstanceId()).setTags(inst_tags);
					ec2.createTags(tag_request);

					// That was a remote request - we should also update our local instance data.
					inst.setTags(inst_tags);
				}
				logger.println("No existing instance found - created: "+inst);
				return newSlave(inst);
			}

			SpotInstanceRequest inst = dsirResult.getSpotInstanceRequests().get(0);
			logger.println("Found existing stopped instance: "+inst);
			List<String> instances = new ArrayList<String>();
			instances.add(inst.getInstanceId());
			StartInstancesRequest siRequest = new StartInstancesRequest(instances);
			StartInstancesResult siResult = ec2.startInstances(siRequest);
			logger.println("Starting existing instance: "+inst+ " result:"+siResult);

			List<Node> nodes = Hudson.getInstance().getNodes();
			for (int i = 0, len = nodes.size(); i < len; i++) {
				if (!(nodes.get(i) instanceof EC2Slave))
					continue;
				EC2Slave ec2Node = (EC2Slave) nodes.get(i);
				if (ec2Node.getInstanceId().equals(inst.getInstanceId())) {
					logger.println("Found existing corresponding: "+ec2Node);
					return ec2Node;
				}
			}

			// Existing slave not found 
			logger.println("Creating new slave for existing instance: "+inst);
			return newSlave(inst);
		}  catch (FormException e) {
			throw new AssertionError(); // we should have discovered all configuration issues upfront
		}
	}

	private EC2Slave newSlave(SpotInstanceRequest inst) throws FormException, IOException {
		AmazonEC2 ec2 = getParent().connect();
		
		Instance requestInst = ec2.describeInstances(new DescribeInstancesRequest().withInstanceIds(inst.getInstanceId()))
			.getReservations().get(0).getInstances().get(0);
		
		return new EC2Slave(inst.getInstanceId(), description, remoteFS, getSshPort(), 
				getNumExecutors(), labels, initScript, remoteAdmin, rootCommandPrefix, 
				jvmopts, stopOnTerminate, idleTerminationMinutes, requestInst.getPublicDnsName(), 
				requestInst.getPrivateDnsName(), EC2Tag.fromAmazonTags(inst.getTags()), usePrivateDnsName);
	}

}
