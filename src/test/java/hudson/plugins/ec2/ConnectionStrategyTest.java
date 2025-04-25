package hudson.plugins.ec2;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Test;

class ConnectionStrategyTest {

    @Test
    void backwardsCompatible_withPrivateDns_notPublicIp() {
        boolean usePrivateDnsName = true;
        boolean connectUsingPublicIp = false;
        boolean associatePublicIp = false;
        assertThat(
                ConnectionStrategy.backwardsCompatible(usePrivateDnsName, connectUsingPublicIp, associatePublicIp),
                equalTo(ConnectionStrategy.PRIVATE_DNS));
    }

    @Test
    void backwardsCompatible_withPrivateDns_withPublicIp() {
        boolean usePrivateDnsName = true;
        boolean connectUsingPublicIp = true;
        boolean associatePublicIp = false;
        assertThat(
                ConnectionStrategy.backwardsCompatible(usePrivateDnsName, connectUsingPublicIp, associatePublicIp),
                equalTo(ConnectionStrategy.PUBLIC_IP));
    }

    @Test
    void backwardsCompatible_withPublicIp() {
        boolean usePrivateDnsName = false;
        boolean connectUsingPublicIp = true;
        boolean associatePublicIp = false;
        assertThat(
                ConnectionStrategy.backwardsCompatible(usePrivateDnsName, connectUsingPublicIp, associatePublicIp),
                equalTo(ConnectionStrategy.PUBLIC_IP));
    }

    @Test
    void backwardsCompatible_withAssociate() {
        boolean usePrivateDnsName = false;
        boolean connectUsingPublicIp = false;
        boolean associatePublicIp = true;
        assertThat(
                ConnectionStrategy.backwardsCompatible(usePrivateDnsName, connectUsingPublicIp, associatePublicIp),
                equalTo(ConnectionStrategy.PUBLIC_IP));
    }

    @Test
    void backwardsCompatible_default() {
        boolean usePrivateDnsName = false;
        boolean connectUsingPublicIp = false;
        boolean associatePublicIp = false;
        assertThat(
                ConnectionStrategy.backwardsCompatible(usePrivateDnsName, connectUsingPublicIp, associatePublicIp),
                equalTo(ConnectionStrategy.PRIVATE_IP));
    }
}
