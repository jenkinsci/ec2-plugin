package hudson.plugins.ec2;

/**
 *
 * Strategy for associating a public IPv4 address with the instance’s primary network interface at launch.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_InstanceNetworkInterfaceSpecification.html">AWS Network Interface Specification</a>
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_Subnet.html">AWS Subnet API</a>
 * */
public enum AssociateIPStrategy {
    SUBNET("Inherit from Subnet"),
    PUBLIC_IP("Public IP"),
    PRIVATE_IP("Private IP"),
    DEFAULT("Default");

    private final String displayText;

    AssociateIPStrategy(String displayText) {
        this.displayText = displayText;
    }

    public String getDisplayText() {
        return this.displayText;
    }

    /**
     * For backwards compatibility.
     * @param associatePublicIp whether or not to use a public ip to establish a connection.
     * @return an {@link AssociateIPStrategy} based on provided parameters that keeps {@code associatePublicIp} behavior.
     */
    public static AssociateIPStrategy backwardsCompatible(boolean associatePublicIp) {
        return associatePublicIp ? PUBLIC_IP : DEFAULT;
    }
}
