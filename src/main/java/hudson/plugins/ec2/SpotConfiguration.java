package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.util.Objects;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public final class SpotConfiguration extends AbstractDescribableImpl<SpotConfiguration>  {
    public final boolean useBidPrice;
    public String spotMaxBidPrice;
    public boolean fallbackToOndemand;
    public int spotBlockReservationDuration;

    @Deprecated
    public SpotConfiguration(boolean useBidPrice, String spotMaxBidPrice, boolean fallbackToOndemand, String spotBlockReservationDurationStr) {
        this.useBidPrice = useBidPrice;
        this.spotMaxBidPrice = spotMaxBidPrice;
        this.fallbackToOndemand = fallbackToOndemand;
        if (null == spotBlockReservationDurationStr || spotBlockReservationDurationStr.isEmpty()) {
            this.spotBlockReservationDuration = 0;
        } else {
            this.spotBlockReservationDuration = Integer.parseInt(spotBlockReservationDurationStr);
        }
    }

    @DataBoundConstructor
    public SpotConfiguration(boolean useBidPrice) {
        this.useBidPrice = useBidPrice;
        this.spotMaxBidPrice = "";
        this.fallbackToOndemand = false;
        this.spotBlockReservationDuration = 0;
    }

    public String getSpotMaxBidPrice() {
        return spotMaxBidPrice;
    }

    @DataBoundSetter
    public void setSpotMaxBidPrice(String spotMaxBidPrice) {
        this.spotMaxBidPrice = spotMaxBidPrice;
    }

    public boolean getFallbackToOndemand() {
        return fallbackToOndemand;
    }

    @DataBoundSetter
    public void setFallbackToOndemand(boolean fallbackToOndemand) {
        this.fallbackToOndemand = fallbackToOndemand;
    }

    public int getSpotBlockReservationDuration() {
        return spotBlockReservationDuration;
    }

    @DataBoundSetter
    public void setSpotBlockReservationDuration(int spotBlockReservationDuration) {
        this.spotBlockReservationDuration = spotBlockReservationDuration;
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

    @Extension
    public static class DescriptorImpl extends Descriptor<SpotConfiguration> {
        @Override
        public String getDisplayName() {
            return "spotConfig";
        }
    }
}
