package hudson.plugins.ec2;

public enum ConnectionStrategy {
    PUBLIC_DNS("Public DNS"),
    PUBLIC_IP("Public IP"),
    PRIVATE_DNS("Private DNS"),
    PRIVATE_IP("Private IP");

    private final String displayText;

    ConnectionStrategy(String displayText) {
        this.displayText = displayText;
    }

    public String getDisplayText() {
        return this.displayText;
    }

    /**
     * For backwards compatibility.
     * @param usePrivateDnsName whether or not to use a private dns to establish a connection.
     * @param connectUsingPublicIp whether or not to use a public ip to establish a connection.
     * @param associatePublicIp whether or not to associate to a public ip.
     * @return an {@link ConnectionStrategy} based on provided parameters.
     */
    public static ConnectionStrategy backwardsCompatible(
            boolean usePrivateDnsName, boolean connectUsingPublicIp, boolean associatePublicIp) {
        if (usePrivateDnsName && !connectUsingPublicIp) {
            return PRIVATE_DNS;
        } else if (connectUsingPublicIp || associatePublicIp) {
            return PUBLIC_IP;
        } else {
            return PRIVATE_IP;
        }
    }
}
