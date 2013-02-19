package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Slave.SlaveDescriptor;
import hudson.util.ListBoxModel;

import org.kohsuke.stapler.DataBoundConstructor;

import com.amazonaws.services.ec2.model.SpotInstanceType;

public final class SpotConfiguration{
	public final String spotMaxBidPrice;
	public final String bidTypes[] = {SpotInstanceType.OneTime.name(), SpotInstanceType.Persistent.name()};
	public final String bidType;
	public transient SpotInstanceType SpotInstanceBidType; 
	
	@DataBoundConstructor
	public SpotConfiguration(String spotMaxBidPrice, String bidType){
		this.spotMaxBidPrice = spotMaxBidPrice;
		this.bidType = bidType;
		this.SpotInstanceBidType = bidType.compareTo(bidTypes[1]) == 0 ? SpotInstanceType.Persistent : SpotInstanceType.OneTime;
	}
	
}