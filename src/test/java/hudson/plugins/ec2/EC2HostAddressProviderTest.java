package hudson.plugins.ec2;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import software.amazon.awssdk.services.ec2.model.Instance;

public class EC2HostAddressProviderTest {

    @Test
    public void unix_publicDnsStrategy_isPresent() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_DNS;

        when(instance.publicDnsName()).thenReturn("ec2-0-0-0-0.compute-1.amazonaws.com");

        assertThat(EC2HostAddressProvider.unix(instance, strategy), equalTo("ec2-0-0-0-0.compute-1.amazonaws.com"));
    }

    @Test
    public void unix_publicDnsStrategy_notPresent() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_DNS;

        when(instance.publicDnsName()).thenReturn("");
        when(instance.publicIpAddress()).thenReturn("0.0.0.0");

        assertThat(EC2HostAddressProvider.unix(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void unix_publicIpStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_IP;

        when(instance.publicIpAddress()).thenReturn("0.0.0.0");

        assertThat(EC2HostAddressProvider.unix(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void unix_privateDnsStrategy_isPresent() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_DNS;

        when(instance.privateDnsName()).thenReturn("0-0-0-0.ec2.internal");

        assertThat(EC2HostAddressProvider.unix(instance, strategy), equalTo("0-0-0-0.ec2.internal"));
    }

    @Test
    public void unix_privateDnsStrategy_notPresent() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_DNS;

        when(instance.privateDnsName()).thenReturn("");
        when(instance.privateIpAddress()).thenReturn("0.0.0.0");

        assertThat(EC2HostAddressProvider.unix(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void unix_privateIpStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_IP;

        when(instance.privateIpAddress()).thenReturn("0.0.0.0");

        assertThat(EC2HostAddressProvider.unix(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void windows_privateDnsStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_DNS;

        when(instance.privateDnsName()).thenReturn("0-0-0-0.ec2.internal");
        when(instance.privateIpAddress()).thenReturn("0.0.0.0");

        assertThat(EC2HostAddressProvider.windows(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void windows_privateIpStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_IP;

        when(instance.privateDnsName()).thenReturn("");
        when(instance.privateIpAddress()).thenReturn("0.0.0.0");

        assertThat(EC2HostAddressProvider.windows(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void windows_publicDnsStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_DNS;

        when(instance.publicDnsName()).thenReturn("ec2-0-0-0-0.compute-1.amazonaws.com");
        when(instance.publicIpAddress()).thenReturn("0.0.0.0");

        assertThat(EC2HostAddressProvider.windows(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void windows_publicIpStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_IP;

        when(instance.publicDnsName()).thenReturn("");
        when(instance.publicIpAddress()).thenReturn("0.0.0.0");

        assertThat(EC2HostAddressProvider.windows(instance, strategy), equalTo("0.0.0.0"));
    }
}
