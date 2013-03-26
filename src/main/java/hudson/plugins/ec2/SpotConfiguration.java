package hudson.plugins.ec2;

import org.kohsuke.stapler.DataBoundConstructor;

public final class SpotConfiguration {
	public final String spotMaxBidPrice;
	
	@DataBoundConstructor
	public SpotConfiguration(String spotMaxBidPrice) {
		this.spotMaxBidPrice = spotMaxBidPrice;
	}

	@Override
	public boolean equals(Object obj) {
		if(obj == null || (this.getClass() != obj.getClass())){
			return false;
		}
		final SpotConfiguration config = (SpotConfiguration) obj;

		return this.spotMaxBidPrice.equals(config.spotMaxBidPrice);
	}
}