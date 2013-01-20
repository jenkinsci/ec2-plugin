package hudson.plugins.ec2;

import org.kohsuke.stapler.DataBoundConstructor;

public final class SpotConfiguration{
	public final String spotMaxBidPrice;
	
	@DataBoundConstructor
	public SpotConfiguration(String spotMaxBidPrice){
		this.spotMaxBidPrice = spotMaxBidPrice;
	}
}