package hudson.plugins.ec2;

import com.amazonaws.services.ec2.model.Instance;
import com.google.common.net.HostAndPort;
import org.apache.commons.lang.StringUtils;

import java.util.Optional;

import static hudson.plugins.ec2.ConnectionStrategy.*;

public class EC2HostAddressProvider {
    public static String unix(Instance instance, ConnectionStrategy strategy) {
        switch (strategy) {
            case PUBLIC_DNS:
                return filterNonEmpty(getPublicDnsName(instance)).orElse(getPublicIpAddress(instance));
            case PUBLIC_IP:
                return getPublicIpAddress(instance);
            case PRIVATE_DNS:
                return filterNonEmpty(getPrivateDnsName(instance)).orElse(getPrivateIpAddress(instance));
            case PRIVATE_IP:
                return getPrivateIpAddress(instance);
            default:
                throw new IllegalArgumentException("Could not unix host address for strategy = " + strategy.toString());
        }
    }

    public static HostAndPort windows(Instance instance, ConnectionStrategy strategy) {
        if (strategy.equals(PRIVATE_DNS) || strategy.equals(PRIVATE_IP)) {
            return HostAndPort.fromString(getPrivateIpAddress(instance));
        } else if (strategy.equals(PUBLIC_DNS) || strategy.equals(PUBLIC_IP)) {
            return HostAndPort.fromString(getPublicIpAddress(instance));
        } else {
            throw new IllegalArgumentException("Could not unix host address for strategy = " + strategy.toString());
        }
    }

    private static String getPublicDnsName(Instance instance) {
        return instance.getPublicDnsName();
    }

    private static String getPublicIpAddress(Instance instance) {
        return instance.getPublicIpAddress();
    }

    private static String getPrivateDnsName(Instance instance) {
        return instance.getPrivateDnsName();
    }

    private static String getPrivateIpAddress(Instance instance) {
        return instance.getPrivateIpAddress();
    }

    private static Optional<String> filterNonEmpty(String value) {
        return Optional.ofNullable(value).filter(StringUtils::isNotEmpty);
    }
}
