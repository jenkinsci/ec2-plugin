package hudson.plugins.ec2;

import org.kohsuke.stapler.DataBoundConstructor;

public final class EnableSpot{
	public final String spotMaxBidPrice;
	
	@DataBoundConstructor
	public EnableSpot(String spotMaxBidPrice){
		this.spotMaxBidPrice = spotMaxBidPrice;
	}
}