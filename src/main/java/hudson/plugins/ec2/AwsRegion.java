package hudson.plugins.ec2;

import org.jvnet.localizer.Localizable;
import org.kohsuke.stapler.Stapler;

import hudson.model.Node.Mode;
import hudson.util.EnumConverter;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * Represents Amazon EC2 regions.
 *
 * @author Kohsuke Kawaguchi
 */
public enum AwsRegion {
    US_EAST_1(Messages._AwsRegion_UsEast()),
    US_WEST_1(Messages._AwsRegion_UsWest()),
    US_WEST_2(Messages._AwsRegion_UsWest2()),
    EU_WEST_1(Messages._AwsRegion_EuWest()),
    AP_SOUTHEAST_1(Messages._AwsRegion_ApSouthEast()),
    AP_NORTHEAST_1(Messages._AwsRegion_ApNorthEast()),
    SA_EAST_1(Messages._AwsRegion_SaEast1());

    public final URL ec2Endpoint,s3Endpoint;

    /**
     * Localized human readable description of this region in a few words.
     */
    public final Localizable displayName;

    AwsRegion(Localizable displayName) {
        try {
            String host = name().toLowerCase(Locale.ENGLISH).replace('_','-');
            ec2Endpoint = new URL("https://"+host+".ec2.amazonaws.com/");
            s3Endpoint  = new URL("https://"+host+".s3.amazonaws.com/");
            this.displayName = displayName;
        } catch (MalformedURLException e) {
            throw new Error(e); // impossible
        }
    }

    @Override
    public String toString() {
        return displayName.toString();
    }

    static {
        Stapler.CONVERT_UTILS.register(new EnumConverter(), AwsRegion.class);
    }
}
