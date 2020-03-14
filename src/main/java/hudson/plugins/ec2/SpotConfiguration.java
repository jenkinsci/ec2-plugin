package hudson.plugins.ec2;

import java.util.Objects;
import org.kohsuke.stapler.DataBoundConstructor;

public final class SpotConfiguration {
    public final boolean useBidPrice;
    public final String spotMaxBidPrice;
    public final boolean fallbackToOndemand;
    public final int spotBlockReservationDuration;

    @DataBoundConstructor public SpotConfiguration(boolean useBidPrice, String spotMaxBidPrice, boolean fallbackToOndemand, String spotBlockReservationDurationStr) {
        this.useBidPrice = useBidPrice;
        this.spotMaxBidPrice = spotMaxBidPrice;
        this.fallbackToOndemand = fallbackToOndemand;
        if (null == spotBlockReservationDurationStr || spotBlockReservationDurationStr.isEmpty()) {
            this.spotBlockReservationDuration = 0;
        } else {
            this.spotBlockReservationDuration = Integer.parseInt(spotBlockReservationDurationStr);
        }
    }

    /**
     * Export the spotBlockReservationDuration attribute for CasC plugin.
     *
     * @return The spotBlockReservationDuration attribute as a string.
     */
    public String getSpotBlockReservationDurationStr() {
        if (spotBlockReservationDuration == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(spotBlockReservationDuration);
        }
    }

    @Override public boolean equals(Object obj) {
        if (obj == null || (this.getClass() != obj.getClass())) {
            return false;
        }
        final SpotConfiguration config = (SpotConfiguration) obj;

        String normalizedBid = normalizeBid(this.spotMaxBidPrice);
        String otherNormalizedBid = normalizeBid(config.spotMaxBidPrice);
        boolean normalizedBidsAreEqual =
                normalizedBid == null ? (otherNormalizedBid == null) : normalizedBid.equals(otherNormalizedBid);
        boolean blockReservationIsEqual = true;
        if (this.spotBlockReservationDuration != config.spotBlockReservationDuration) {
            blockReservationIsEqual = false;
        }

        return this.useBidPrice == config.useBidPrice && this.fallbackToOndemand == config.fallbackToOndemand
                && normalizedBidsAreEqual && blockReservationIsEqual;
    }

    @Override public int hashCode() {
        return Objects.hash(useBidPrice, spotMaxBidPrice, fallbackToOndemand);
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
                return null;
            }
            return spotPrice.toString();
        } catch (NumberFormatException ex) {
            return null;
        }

    }
}
