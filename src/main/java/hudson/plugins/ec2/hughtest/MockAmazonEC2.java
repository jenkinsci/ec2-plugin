package hudson.plugins.ec2.hughtest;

//import org.mockito.Mockito;

import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Random;

import org.mockito.Mockito;

import com.amazonaws.*;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;

public class MockAmazonEC2 implements AmazonEC2 {
	private ArrayList<Instance> instances = new ArrayList<Instance>();
	private String keyName;
	private String privateKey;
	private String fingerprint;
	
	void printcall() {
		StackTraceElement me = Thread.currentThread().getStackTrace()[2];
		System.out.println(me.getClassName() + "." + me.getMethodName() + " " + me.getLineNumber() );
	}
	
	public DescribeReservedInstancesResult describeReservedInstances(
			DescribeReservedInstancesRequest describeReservedInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}
	

	public void setKeyName(String keyName ){
		this.keyName = keyName;
	}
	
	public void setPrivateKey( String privateKey ) {
		this.privateKey = privateKey;
	}
	
	public String getKeyname() {
		return keyName;
	}
	
	public String getPrivateKey() {
		return privateKey;
	}
	
	public void setFingerprint( String fingerprint ) {
		this.fingerprint = fingerprint;
	}
	
	public String getFingerprint() {
		return fingerprint;
	}
	
	public DescribeAvailabilityZonesResult describeAvailabilityZones(
			DescribeAvailabilityZonesRequest describeAvailabilityZonesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DetachVolumeResult detachVolume(
			DetachVolumeRequest detachVolumeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void deleteKeyPair(DeleteKeyPairRequest deleteKeyPairRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public UnmonitorInstancesResult unmonitorInstances(
			UnmonitorInstancesRequest unmonitorInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public AttachVpnGatewayResult attachVpnGateway(
			AttachVpnGatewayRequest attachVpnGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CreateImageResult createImage(CreateImageRequest createImageRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void deleteSecurityGroup(
			DeleteSecurityGroupRequest deleteSecurityGroupRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CreateInstanceExportTaskResult createInstanceExportTask(
			CreateInstanceExportTaskRequest createInstanceExportTaskRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void authorizeSecurityGroupEgress(
			AuthorizeSecurityGroupEgressRequest authorizeSecurityGroupEgressRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public GetPasswordDataResult getPasswordData(
			GetPasswordDataRequest getPasswordDataRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void associateDhcpOptions(
			AssociateDhcpOptionsRequest associateDhcpOptionsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public StopInstancesResult stopInstances(
			StopInstancesRequest stopInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public ImportKeyPairResult importKeyPair(
			ImportKeyPairRequest importKeyPairRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void deleteNetworkInterface(
			DeleteNetworkInterfaceRequest deleteNetworkInterfaceRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CreateSecurityGroupResult createSecurityGroup(
			CreateSecurityGroupRequest createSecurityGroupRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeSpotPriceHistoryResult describeSpotPriceHistory(
			DescribeSpotPriceHistoryRequest describeSpotPriceHistoryRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeNetworkInterfacesResult describeNetworkInterfaces(
			DescribeNetworkInterfacesRequest describeNetworkInterfacesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeRegionsResult describeRegions(
			DescribeRegionsRequest describeRegionsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CreateReservedInstancesListingResult createReservedInstancesListing(
			CreateReservedInstancesListingRequest createReservedInstancesListingRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CreateDhcpOptionsResult createDhcpOptions(
			CreateDhcpOptionsRequest createDhcpOptionsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void resetSnapshotAttribute(
			ResetSnapshotAttributeRequest resetSnapshotAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteRoute(DeleteRouteRequest deleteRouteRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeInternetGatewaysResult describeInternetGateways(
			DescribeInternetGatewaysRequest describeInternetGatewaysRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public ImportVolumeResult importVolume(
			ImportVolumeRequest importVolumeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeSecurityGroupsResult describeSecurityGroups(
			DescribeSecurityGroupsRequest describeSecurityGroupsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void detachVpnGateway(DetachVpnGatewayRequest detachVpnGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deregisterImage(DeregisterImageRequest deregisterImageRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeSpotDatafeedSubscriptionResult describeSpotDatafeedSubscription(
			DescribeSpotDatafeedSubscriptionRequest describeSpotDatafeedSubscriptionRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void deleteTags(DeleteTagsRequest deleteTagsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteSubnet(DeleteSubnetRequest deleteSubnetRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CreateVpnGatewayResult createVpnGateway(
			CreateVpnGatewayRequest createVpnGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void enableVolumeIO(EnableVolumeIORequest enableVolumeIORequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteVpnGateway(DeleteVpnGatewayRequest deleteVpnGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public AttachVolumeResult attachVolume(
			AttachVolumeRequest attachVolumeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeLicensesResult describeLicenses(
			DescribeLicensesRequest describeLicensesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVolumeStatusResult describeVolumeStatus(
			DescribeVolumeStatusRequest describeVolumeStatusRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void activateLicense(ActivateLicenseRequest activateLicenseRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void resetImageAttribute(
			ResetImageAttributeRequest resetImageAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeVpnConnectionsResult describeVpnConnections(
			DescribeVpnConnectionsRequest describeVpnConnectionsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void enableVgwRoutePropagation(
			EnableVgwRoutePropagationRequest enableVgwRoutePropagationRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CreateSnapshotResult createSnapshot(
			CreateSnapshotRequest createSnapshotRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void deleteVolume(DeleteVolumeRequest deleteVolumeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CreateNetworkInterfaceResult createNetworkInterface(
			CreateNetworkInterfaceRequest createNetworkInterfaceRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVpcsResult describeVpcs(
			DescribeVpcsRequest describeVpcsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void unassignPrivateIpAddresses(
			UnassignPrivateIpAddressesRequest unassignPrivateIpAddressesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public AssociateAddressResult associateAddress(
			AssociateAddressRequest associateAddressRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void cancelConversionTask(
			CancelConversionTaskRequest cancelConversionTaskRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deactivateLicense(
			DeactivateLicenseRequest deactivateLicenseRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteCustomerGateway(
			DeleteCustomerGatewayRequest deleteCustomerGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void createNetworkAclEntry(
			CreateNetworkAclEntryRequest createNetworkAclEntryRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeExportTasksResult describeExportTasks(
			DescribeExportTasksRequest describeExportTasksRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void detachInternetGateway(
			DetachInternetGatewayRequest detachInternetGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CreateRouteTableResult createRouteTable(
			CreateRouteTableRequest createRouteTableRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVolumesResult describeVolumes(
			DescribeVolumesRequest describeVolumesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeReservedInstancesListingsResult describeReservedInstancesListings(
			DescribeReservedInstancesListingsRequest describeReservedInstancesListingsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void reportInstanceStatus(
			ReportInstanceStatusRequest reportInstanceStatusRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeRouteTablesResult describeRouteTables(
			DescribeRouteTablesRequest describeRouteTablesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeDhcpOptionsResult describeDhcpOptions(
			DescribeDhcpOptionsRequest describeDhcpOptionsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public MonitorInstancesResult monitorInstances(
			MonitorInstancesRequest monitorInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeNetworkAclsResult describeNetworkAcls(
			DescribeNetworkAclsRequest describeNetworkAclsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeBundleTasksResult describeBundleTasks(
			DescribeBundleTasksRequest describeBundleTasksRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public ImportInstanceResult importInstance(
			ImportInstanceRequest importInstanceRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void revokeSecurityGroupIngress(
			RevokeSecurityGroupIngressRequest revokeSecurityGroupIngressRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public GetConsoleOutputResult getConsoleOutput(
			GetConsoleOutputRequest getConsoleOutputRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CreateInternetGatewayResult createInternetGateway(
			CreateInternetGatewayRequest createInternetGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void deleteVpnConnectionRoute(
			DeleteVpnConnectionRouteRequest deleteVpnConnectionRouteRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void detachNetworkInterface(
			DetachNetworkInterfaceRequest detachNetworkInterfaceRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void modifyImageAttribute(
			ModifyImageAttributeRequest modifyImageAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CreateCustomerGatewayResult createCustomerGateway(
			CreateCustomerGatewayRequest createCustomerGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CreateSpotDatafeedSubscriptionResult createSpotDatafeedSubscription(
			CreateSpotDatafeedSubscriptionRequest createSpotDatafeedSubscriptionRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void attachInternetGateway(
			AttachInternetGatewayRequest attachInternetGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteVpnConnection(
			DeleteVpnConnectionRequest deleteVpnConnectionRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeConversionTasksResult describeConversionTasks(
			DescribeConversionTasksRequest describeConversionTasksRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CreateVpnConnectionResult createVpnConnection(
			CreateVpnConnectionRequest createVpnConnectionRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeInstanceAttributeResult describeInstanceAttribute(
			DescribeInstanceAttributeRequest describeInstanceAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeSubnetsResult describeSubnets(
			DescribeSubnetsRequest describeSubnetsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public RunInstancesResult runInstances(
			RunInstancesRequest runInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		System.out.println("runInstances, request instancetype " + runInstancesRequest.getInstanceType() +
				" keyname " + runInstancesRequest.getKeyName() + " imageid " + runInstancesRequest.getImageId() +
				" securitygroup " + runInstancesRequest.getSecurityGroups().get(0)
				+ " userdata " + runInstancesRequest.getUserData() );

		Instance instance = new Instance();
		Random random = new Random();
		instance.setInstanceId("" + random.nextInt(10000));
		InstanceState instanceState = new InstanceState();
		instanceState.setName(InstanceStateName.Running);
		instance.setState(instanceState);
		System.out.println("runinstances() instance " + instance );

		ArrayList<Instance> instances = new ArrayList<Instance>();
		instances.add(instance);
		this.instances.add(instance);
		Reservation reservation = new Reservation();
		reservation.setInstances(instances);
		RunInstancesResult runInstancesResult = new RunInstancesResult();
		runInstancesResult.setReservation(reservation);
		return runInstancesResult;
	}


	public DescribePlacementGroupsResult describePlacementGroups(
			DescribePlacementGroupsRequest describePlacementGroupsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public AssociateRouteTableResult associateRouteTable(
			AssociateRouteTableRequest associateRouteTableRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeInstancesResult describeInstances(
			DescribeInstancesRequest describeInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		System.out.println("describeInstances()");
		DescribeInstancesResult describeInstancesResult = new DescribeInstancesResult();
		if( instances.size() == 0 ) {
			return describeInstancesResult;
		}
		Reservation reservation = new Reservation();
		ArrayList<Reservation> reservations = new ArrayList<>();
		reservations.add( reservation );
		describeInstancesResult.setReservations(reservations);
		reservation.setInstances(instances);

		System.out.println("dumping instances:");
		for( Reservation _reservation : describeInstancesResult.getReservations() ) {
			System.out.println("reservation id " + _reservation.getReservationId());
		System.out.println("dumping instances");
			for( Instance instance : _reservation.getInstances() ) {
				System.out.println("instance instanceid " + instance.getInstanceId() + 
						" imageid " + instance.getImageId() + " state " + instance.getState() );
			}
		}

		//		DescribeInstancesResult describeInstancesResult = Mockito.mock(DescribeInstancesResult.class);
		return describeInstancesResult;
	}


	public void deleteNetworkAcl(DeleteNetworkAclRequest deleteNetworkAclRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void modifyVolumeAttribute(
			ModifyVolumeAttributeRequest modifyVolumeAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeImagesResult describeImages(
			DescribeImagesRequest describeImagesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public StartInstancesResult startInstances(
			StartInstancesRequest startInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CancelReservedInstancesListingResult cancelReservedInstancesListing(
			CancelReservedInstancesListingRequest cancelReservedInstancesListingRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void modifyInstanceAttribute(
			ModifyInstanceAttributeRequest modifyInstanceAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteDhcpOptions(
			DeleteDhcpOptionsRequest deleteDhcpOptionsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void authorizeSecurityGroupIngress(
			AuthorizeSecurityGroupIngressRequest authorizeSecurityGroupIngressRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeSpotInstanceRequestsResult describeSpotInstanceRequests(
			DescribeSpotInstanceRequestsRequest describeSpotInstanceRequestsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CreateVpcResult createVpc(CreateVpcRequest createVpcRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeCustomerGatewaysResult describeCustomerGateways(
			DescribeCustomerGatewaysRequest describeCustomerGatewaysRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void cancelExportTask(CancelExportTaskRequest cancelExportTaskRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void createRoute(CreateRouteRequest createRouteRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void modifyNetworkInterfaceAttribute(
			ModifyNetworkInterfaceAttributeRequest modifyNetworkInterfaceAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteRouteTable(DeleteRouteTableRequest deleteRouteTableRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeNetworkInterfaceAttributeResult describeNetworkInterfaceAttribute(
			DescribeNetworkInterfaceAttributeRequest describeNetworkInterfaceAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public RequestSpotInstancesResult requestSpotInstances(
			RequestSpotInstancesRequest requestSpotInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void createTags(CreateTagsRequest createTagsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public AttachNetworkInterfaceResult attachNetworkInterface(
			AttachNetworkInterfaceRequest attachNetworkInterfaceRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVolumeAttributeResult describeVolumeAttribute(
			DescribeVolumeAttributeRequest describeVolumeAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void replaceRoute(ReplaceRouteRequest replaceRouteRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeTagsResult describeTags(
			DescribeTagsRequest describeTagsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CancelBundleTaskResult cancelBundleTask(
			CancelBundleTaskRequest cancelBundleTaskRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void disableVgwRoutePropagation(
			DisableVgwRoutePropagationRequest disableVgwRoutePropagationRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CancelSpotInstanceRequestsResult cancelSpotInstanceRequests(
			CancelSpotInstanceRequestsRequest cancelSpotInstanceRequestsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public PurchaseReservedInstancesOfferingResult purchaseReservedInstancesOffering(
			PurchaseReservedInstancesOfferingRequest purchaseReservedInstancesOfferingRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void modifySnapshotAttribute(
			ModifySnapshotAttributeRequest modifySnapshotAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public TerminateInstancesResult terminateInstances(
			TerminateInstancesRequest terminateInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void deleteSpotDatafeedSubscription(
			DeleteSpotDatafeedSubscriptionRequest deleteSpotDatafeedSubscriptionRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteInternetGateway(
			DeleteInternetGatewayRequest deleteInternetGatewayRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeSnapshotAttributeResult describeSnapshotAttribute(
			DescribeSnapshotAttributeRequest describeSnapshotAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public ReplaceRouteTableAssociationResult replaceRouteTableAssociation(
			ReplaceRouteTableAssociationRequest replaceRouteTableAssociationRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeAddressesResult describeAddresses(
			DescribeAddressesRequest describeAddressesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeImageAttributeResult describeImageAttribute(
			DescribeImageAttributeRequest describeImageAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeKeyPairsResult describeKeyPairs(
			DescribeKeyPairsRequest describeKeyPairsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public ConfirmProductInstanceResult confirmProductInstance(
			ConfirmProductInstanceRequest confirmProductInstanceRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void disassociateRouteTable(
			DisassociateRouteTableRequest disassociateRouteTableRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void revokeSecurityGroupEgress(
			RevokeSecurityGroupEgressRequest revokeSecurityGroupEgressRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteNetworkAclEntry(
			DeleteNetworkAclEntryRequest deleteNetworkAclEntryRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CreateVolumeResult createVolume(
			CreateVolumeRequest createVolumeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeInstanceStatusResult describeInstanceStatus(
			DescribeInstanceStatusRequest describeInstanceStatusRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVpnGatewaysResult describeVpnGateways(
			DescribeVpnGatewaysRequest describeVpnGatewaysRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CreateSubnetResult createSubnet(
			CreateSubnetRequest createSubnetRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeReservedInstancesOfferingsResult describeReservedInstancesOfferings(
			DescribeReservedInstancesOfferingsRequest describeReservedInstancesOfferingsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void assignPrivateIpAddresses(
			AssignPrivateIpAddressesRequest assignPrivateIpAddressesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteSnapshot(DeleteSnapshotRequest deleteSnapshotRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public ReplaceNetworkAclAssociationResult replaceNetworkAclAssociation(
			ReplaceNetworkAclAssociationRequest replaceNetworkAclAssociationRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void disassociateAddress(
			DisassociateAddressRequest disassociateAddressRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void createPlacementGroup(
			CreatePlacementGroupRequest createPlacementGroupRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public BundleInstanceResult bundleInstance(
			BundleInstanceRequest bundleInstanceRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void deletePlacementGroup(
			DeletePlacementGroupRequest deletePlacementGroupRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void deleteVpc(DeleteVpcRequest deleteVpcRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CopySnapshotResult copySnapshot(
			CopySnapshotRequest copySnapshotRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public AllocateAddressResult allocateAddress(
			AllocateAddressRequest allocateAddressRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void releaseAddress(ReleaseAddressRequest releaseAddressRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void resetInstanceAttribute(
			ResetInstanceAttributeRequest resetInstanceAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public CreateKeyPairResult createKeyPair(
			CreateKeyPairRequest createKeyPairRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void replaceNetworkAclEntry(
			ReplaceNetworkAclEntryRequest replaceNetworkAclEntryRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeSnapshotsResult describeSnapshots(
			DescribeSnapshotsRequest describeSnapshotsRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public CreateNetworkAclResult createNetworkAcl(
			CreateNetworkAclRequest createNetworkAclRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public RegisterImageResult registerImage(
			RegisterImageRequest registerImageRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void resetNetworkInterfaceAttribute(
			ResetNetworkInterfaceAttributeRequest resetNetworkInterfaceAttributeRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public void createVpnConnectionRoute(
			CreateVpnConnectionRouteRequest createVpnConnectionRouteRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}


	public DescribeReservedInstancesResult describeReservedInstances()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeAvailabilityZonesResult describeAvailabilityZones()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void deleteSecurityGroup() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		
	}


	public DescribeSpotPriceHistoryResult describeSpotPriceHistory()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeNetworkInterfacesResult describeNetworkInterfaces()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeRegionsResult describeRegions()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeInternetGatewaysResult describeInternetGateways()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public ImportVolumeResult importVolume() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		return null;
	}


	public DescribeSecurityGroupsResult describeSecurityGroups()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeSpotDatafeedSubscriptionResult describeSpotDatafeedSubscription()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeLicensesResult describeLicenses()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVolumeStatusResult describeVolumeStatus()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVpnConnectionsResult describeVpnConnections()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVpcsResult describeVpcs() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		return null;
	}


	public DescribeExportTasksResult describeExportTasks()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVolumesResult describeVolumes()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeReservedInstancesListingsResult describeReservedInstancesListings()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void reportInstanceStatus() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		
	}


	public DescribeRouteTablesResult describeRouteTables()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeDhcpOptionsResult describeDhcpOptions()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeNetworkAclsResult describeNetworkAcls()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeBundleTasksResult describeBundleTasks()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void revokeSecurityGroupIngress() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		
	}


	public CreateInternetGatewayResult createInternetGateway()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeConversionTasksResult describeConversionTasks()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeSubnetsResult describeSubnets()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribePlacementGroupsResult describePlacementGroups()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeInstancesResult describeInstances()
			throws AmazonServiceException, AmazonClientException {
		System.out.println("MockAmazonEC2.describeinstances() num instances: " + this.instances.size() );
		printcall();
		DescribeInstancesResult describeInstancesResult = new DescribeInstancesResult();
		ArrayList<Reservation> reservations = new ArrayList<Reservation>();
		System.out.println("dumping instances:");
		for( Reservation reservation : reservations ) {
			System.out.println("reservation id " + reservation.getReservationId());
			for( Instance instance : reservation.getInstances() ) {
				System.out.println("instance instanceid " + instance.getInstanceId() + 
						" imageid " + instance.getImageId() + " state " + instance.getState() );
			}
		}
		describeInstancesResult.setReservations(reservations);
		Reservation reservation = new Reservation();
//		Reservation reservation = Mockito.mock(Reservation.class);
		reservation.setInstances(instances);
//		when(reservation.getInstances()).thenReturn(instances);
		reservations.add(reservation);
//		DescribeInstancesResult describeInstancesResult = Mockito.mock(DescribeInstancesResult.class);
//		when(describeInstancesResult.getReservations()).thenReturn(new ArrayList<Reservation>());
		return describeInstancesResult;
	}


	public DescribeImagesResult describeImages() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		return null;
	}


	public void authorizeSecurityGroupIngress() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		
	}


	public DescribeSpotInstanceRequestsResult describeSpotInstanceRequests()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeCustomerGatewaysResult describeCustomerGateways()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeTagsResult describeTags() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		return null;
	}


	public void deleteSpotDatafeedSubscription() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		
	}


	public DescribeAddressesResult describeAddresses()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeKeyPairsResult describeKeyPairs()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		DescribeKeyPairsResult describeKeyPairsResult = new DescribeKeyPairsResult();
		ArrayList<KeyPairInfo> keyPairs = new ArrayList<KeyPairInfo>();
		KeyPairInfo myKey = new KeyPairInfo();
		myKey.setKeyFingerprint(fingerprint);
		myKey.setKeyName(keyName);
//		myKey.setKeyFingerprint("mymockfingerprint");
//		myKey.setKeyName("mymockkeyname");
		System.out.println("mock keypair: fingerprint: mymockfingerprint, keyname: mymockkeyname");
		keyPairs.add(myKey);
		describeKeyPairsResult.setKeyPairs(keyPairs);
		return describeKeyPairsResult;
	}


	public DescribeInstanceStatusResult describeInstanceStatus()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeVpnGatewaysResult describeVpnGateways()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public DescribeReservedInstancesOfferingsResult describeReservedInstancesOfferings()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public AllocateAddressResult allocateAddress()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public void releaseAddress() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		
	}


	public DescribeSnapshotsResult describeSnapshots()
			throws AmazonServiceException, AmazonClientException {
		printcall();
		return null;
	}


	public RegisterImageResult registerImage() throws AmazonServiceException,
			AmazonClientException {
		printcall();
		return null;
	}


	public void shutdown() {
		printcall();
		
	}


	public ResponseMetadata getCachedResponseMetadata(
			AmazonWebServiceRequest request) {
		printcall();
		return null;
	}

	public void setEndpoint(String endpoint) throws IllegalArgumentException {
		printcall();
		
	}


	public void rebootInstances(RebootInstancesRequest rebootInstancesRequest)
			throws AmazonServiceException, AmazonClientException {
		printcall();
		
	}

}
