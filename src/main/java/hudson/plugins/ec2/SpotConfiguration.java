package hudson.plugins.ec2;

import org.kohsuke.stapler.DataBoundConstructor;

public final class SpotConfiguration {
    public final boolean useBidPrice;
    public final String spotMaxBidPrice;
    public final boolean fallbackToOndemand;

    @DataBoundConstructor public SpotConfiguration(boolean useBidPrice, String spotMaxBidPrice, boolean fallbackToOndemand) {
        this.useBidPrice = useBidPrice;
        this.spotMaxBidPrice = spotMaxBidPrice;
        this.fallbackToOndemand = fallbackToOndemand;
    }

    @Override public boolean equals(Object obj) {
        if (obj == null || (this.getClass() != obj.getClass())) {
            return false;
        }
        final SpotConfiguration config = (SpotConfiguration) obj;

        return this.useBidPrice == config.useBidPrice && this.fallbackToOndemand == config.fallbackToOndemand
                && normalizeBid(this.spotMaxBidPrice).equals(normalizeBid(config.spotMaxBidPrice));
    }

    /**
     * Check if the specified value is a valid bid price to make a Spot request and return the normalized string for the
     * float of the specified bid Bids must be &gt;= .001
     *
     * @param bid - price to check
     * @return The normalized string for a Float if possible, otherwise null
     */
    public static String normalizeBid(String bid) {
        try {
            Float spotPrice = Float.parseFloat(bid);

            /* The specified bid price cannot be less than 0.001 */
            if (spotPrice < 0.001) {
                return "";
            }
            return spotPrice.toString();
        } catch (NumberFormatException ex) {
            return "";
        }

    }
}
