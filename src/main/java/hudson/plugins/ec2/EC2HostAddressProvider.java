package hudson.plugins.ec2;

import com.amazonaws.services.ec2.model.Instance;
import java.util.Optional;
import org.apache.commons.lang.StringUtils;

public class EC2HostAddressProvider {
    public static String unix(Instance instance, ConnectionStrategy strategy) {
        return switch (strategy) {
            case PUBLIC_DNS -> filterNonEmpty(getPublicDnsName(instance)).orElse(getPublicIpAddress(instance));
            case PUBLIC_IP -> getPublicIpAddress(instance);
            case PRIVATE_DNS -> filterNonEmpty(getPrivateDnsName(instance)).orElse(getPrivateIpAddress(instance));
            case PRIVATE_IP -> getPrivateIpAddress(instance);
        };
    }

    public static String mac(Instance instance, ConnectionStrategy strategy) {
        return switch (strategy) {
            case PUBLIC_DNS -> filterNonEmpty(getPublicDnsName(instance)).orElse(getPublicIpAddress(instance));
            case PUBLIC_IP -> getPublicIpAddress(instance);
            case PRIVATE_DNS -> filterNonEmpty(getPrivateDnsName(instance)).orElse(getPrivateIpAddress(instance));
            case PRIVATE_IP -> getPrivateIpAddress(instance);
        };
    }

    public static String windows(Instance instance, ConnectionStrategy strategy) {
        if (strategy.equals(ConnectionStrategy.PRIVATE_DNS) || strategy.equals(ConnectionStrategy.PRIVATE_IP)) {
            return getPrivateIpAddress(instance);
        } else if (strategy.equals(ConnectionStrategy.PUBLIC_DNS) || strategy.equals(ConnectionStrategy.PUBLIC_IP)) {
            return getPublicIpAddress(instance);
        } else {
            throw new IllegalArgumentException("Could not windows host address for strategy = " + strategy);
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
