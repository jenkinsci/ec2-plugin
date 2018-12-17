package hudson.plugins.ec2;

import org.kohsuke.stapler.DataBoundConstructor;

public final class SpotConfiguration {
    public final String spotMaxBidPrice;
    public final int spotBlockReservationDuration;

    @DataBoundConstructor
    public SpotConfiguration(String spotMaxBidPrice, String spotBlockReservationDurationStr) {
        this.spotMaxBidPrice = spotMaxBidPrice;
        if (null == spotBlockReservationDurationStr || spotBlockReservationDurationStr.isEmpty()) {
            this.spotBlockReservationDuration = 0;
        } else {
            this.spotBlockReservationDuration = Integer.parseInt(spotBlockReservationDurationStr);
        }
    }

    /*
     * Backwards compat with the unit tests
     */
    public SpotConfiguration(String spotMaxBidPrice) {
        this(spotMaxBidPrice, null);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || (this.getClass() != obj.getClass())) {
            return false;
        }
        final SpotConfiguration config = (SpotConfiguration) obj;

        if (this.spotBlockReservationDuration != config.spotBlockReservationDuration) {
            return false;
        }
        return normalizeBid(this.spotMaxBidPrice).equals(normalizeBid(config.spotMaxBidPrice));
    }

    /**
     * Check if the specified value is a valid bid price to make a Spot request and return the normalized string for the
     * float of the specified bid Bids must be &gt;= .001
     *
     * @param bid
     *            - price to check
     * @return The normalized string for a Float if possible, otherwise null
     */
    public static String normalizeBid(String bid) {
        try {
            Float spotPrice = Float.parseFloat(bid);

            /* The specified bid price cannot be less than 0.001 */
            if (spotPrice < 0.001) {
                return null;
            }
            return spotPrice.toString();
        } catch (NumberFormatException ex) {
            return null;
        }

    }
}
