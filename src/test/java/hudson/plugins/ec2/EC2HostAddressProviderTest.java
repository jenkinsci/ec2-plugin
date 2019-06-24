package hudson.plugins.ec2;

import static hudson.plugins.ec2.EC2HostAddressProvider.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.ec2.model.Instance;
import com.google.common.net.HostAndPort;
import org.junit.Test;

public class EC2HostAddressProviderTest {

    @Test
    public void unix_publicDnsStrategy_isPresent() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_DNS;

        when(instance.getPublicDnsName()).thenReturn("ec2-0-0-0-0.compute-1.amazonaws.com");

        assertThat(unix(instance, strategy), equalTo("ec2-0-0-0-0.compute-1.amazonaws.com"));
    }

    @Test
    public void unix_publicDnsStrategy_notPresent() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_DNS;

        when(instance.getPublicDnsName()).thenReturn("");
        when(instance.getPublicIpAddress()).thenReturn("0.0.0.0");

        assertThat(unix(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void unix_publicIpStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_IP;

        when(instance.getPublicIpAddress()).thenReturn("0.0.0.0");

        assertThat(unix(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void unix_privateDnsStrategy_isPresent() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_DNS;

        when(instance.getPrivateDnsName()).thenReturn("0-0-0-0.ec2.internal");

        assertThat(unix(instance, strategy), equalTo("0-0-0-0.ec2.internal"));
    }

    @Test
    public void unix_privateDnsStrategy_notPresent() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_DNS;

        when(instance.getPrivateDnsName()).thenReturn("");
        when(instance.getPrivateIpAddress()).thenReturn("0.0.0.0");

        assertThat(unix(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void unix_privateIpStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_IP;

        when(instance.getPrivateIpAddress()).thenReturn("0.0.0.0");

        assertThat(unix(instance, strategy), equalTo("0.0.0.0"));
    }


    @Test
    public void windows_privateDnsStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_DNS;

        when(instance.getPrivateDnsName()).thenReturn("0-0-0-0.ec2.internal");
        when(instance.getPrivateIpAddress()).thenReturn("0.0.0.0");

        assertThat(windows(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void windows_privateIpStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PRIVATE_IP;

        when(instance.getPrivateDnsName()).thenReturn("");
        when(instance.getPrivateIpAddress()).thenReturn("0.0.0.0");

        assertThat(windows(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void windows_publicDnsStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_DNS;

        when(instance.getPublicDnsName()).thenReturn("ec2-0-0-0-0.compute-1.amazonaws.com");
        when(instance.getPublicIpAddress()).thenReturn("0.0.0.0");

        assertThat(windows(instance, strategy), equalTo("0.0.0.0"));
    }

    @Test
    public void windows_publicIpStrategy() {
        Instance instance = mock(Instance.class);
        ConnectionStrategy strategy = ConnectionStrategy.PUBLIC_IP;

        when(instance.getPublicDnsName()).thenReturn("");
        when(instance.getPublicIpAddress()).thenReturn("0.0.0.0");

        assertThat(windows(instance, strategy), equalTo("0.0.0.0"));
    }
}
