package hudson.plugins.ec2;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryRequest;
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryResult;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.SpotPrice;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.plugins.ec2.util.AmazonEC2Factory;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Objects;
import javax.servlet.ServletException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import static hudson.Functions.checkPermission;

public final class SpotConfiguration extends AbstractDescribableImpl<SpotConfiguration>  {
    public final boolean useBidPrice;
    private String spotMaxBidPrice;
    private boolean fallbackToOndemand;
    private int spotBlockReservationDuration;

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

        /*
         * Check the current Spot price of the selected instance type for the selected region
         */
        @RequirePOST
        public FormValidation doCurrentSpotPrice(@QueryParameter boolean useInstanceProfileForCredentials,
                @QueryParameter String credentialsId, @QueryParameter String region,
                @QueryParameter String type, @QueryParameter String zone, @QueryParameter String roleArn,
                @QueryParameter String roleSessionName, @QueryParameter String ami) throws IOException, ServletException {

            checkPermission(EC2Cloud.PROVISION);

            String cp = "";
            String zoneStr = "";

            // Connect to the EC2 cloud with the access id, secret key, and
            // region queried from the created cloud
            AWSCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
            AmazonEC2 ec2 = AmazonEC2Factory.getInstance().connect(credentialsProvider, AmazonEC2Cloud.getEc2EndpointUrl(region));

            if (ec2 != null) {

                try {
                    // Build a new price history request with the currently
                    // selected type
                    DescribeSpotPriceHistoryRequest request = new DescribeSpotPriceHistoryRequest();
                    // If a zone is specified, set the availability zone in the
                    // request
                    // Else, proceed with no availability zone which will result
                    // with the cheapest Spot price
                    if (CloudHelper.getAvailabilityZones(ec2).contains(zone)) {
                        request.setAvailabilityZone(zone);
                        zoneStr = zone + " availability zone";
                    } else {
                        zoneStr = region + " region";
                    }

                    /*
                     * Iterate through the AWS instance types to see if can find a match for the databound String type.
                     * This is necessary because the AWS API needs the instance type string formatted a particular way
                     * to retrieve prices and the form gives us the strings in a different format. For example "T1Micro"
                     * vs "t1.micro".
                     */
                    InstanceType ec2Type = null;

                    for (InstanceType it : InstanceType.values()) {
                        if (it.name().equals(type)) {
                            ec2Type = it;
                            break;
                        }
                    }

                    /*
                     * If the type string cannot be matched with an instance type, throw a Form error
                     */
                    if (ec2Type == null) {
                        return FormValidation.error("Could not resolve instance type: " + type);
                    }

                    if (!ami.isEmpty()) {
                        Image img = CloudHelper.getAmiImage(ec2, ami);
                        if (img != null) {
                            Collection<String> productDescriptions = new ArrayList<>();
                            productDescriptions.add(img.getPlatform() == "Windows" ? "Windows" : "Linux/UNIX");
                            request.setProductDescriptions(productDescriptions);
                        }
                    }

                    Collection<String> instanceType = new ArrayList<>();
                    instanceType.add(ec2Type.toString());
                    request.setInstanceTypes(instanceType);
                    request.setStartTime(new Date());

                    // Retrieve the price history request result and store the
                    // current price
                    DescribeSpotPriceHistoryResult result = ec2.describeSpotPriceHistory(request);

                    if (!result.getSpotPriceHistory().isEmpty()) {
                        SpotPrice currentPrice = result.getSpotPriceHistory().get(0);

                        cp = currentPrice.getSpotPrice();
                    }

                } catch (AmazonServiceException e) {
                    return FormValidation.error(e.getMessage());
                }
            }
            /*
             * If we could not return the current price of the instance display an error Else, remove the additional
             * zeros from the current price and return it to the interface in the form of a message
             */
            if (cp.isEmpty()) {
                return FormValidation.error("Could not retrieve current Spot price");
            } else {
                cp = cp.substring(0, cp.length() - 3);

                return FormValidation.ok("The current Spot price for a " + type + " in the " + zoneStr + " is $" + cp);
            }
        }
    }
}
