package hudson.plugins.ec2;

import org.kohsuke.stapler.DataBoundConstructor;
import com.amazonaws.services.ec2.model.SpotInstanceType;

public final class SpotConfiguration {
	public final String spotMaxBidPrice;
	public final String spotInstanceBidType;
	
	@DataBoundConstructor
	public SpotConfiguration(String spotMaxBidPrice, String bidType) {
		this.spotMaxBidPrice = spotMaxBidPrice;
		this.spotInstanceBidType = bidType;
	}

	@Override
	public boolean equals(Object obj) {
		if(obj == null || (this.getClass() != obj.getClass())) {
			return false;
		}
		final SpotConfiguration config = (SpotConfiguration) obj;

		return normalizeBid(this.spotMaxBidPrice).equals(normalizeBid(config.spotMaxBidPrice)) &&
				this.spotInstanceBidType.equals(config.spotInstanceBidType);
	}

	/**
	 * Check if the specified value is a valid bid price to make a Spot request
	 * and return the normalized string for the float of the specified bid
	 * Bids must be &gt;= .001
	 * @param bid - price to check
	 * @return The normalized string for a Float if possible, otherwise null
	 */
	public static String normalizeBid(String bid){
		try {
			Float spotPrice = Float.parseFloat(bid);

			/* The specified bid price cannot be less than 0.001 */
			if(spotPrice < 0.001) {
				return null;
			}
			return spotPrice.toString();
		} catch (NumberFormatException ex) {
			return null;
		}

	}
}
