/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import org.junit.Test;

import com.amazonaws.services.ec2.model.Tag;

import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import hudson.plugins.ec2.util.AmazonEC2FactoryMockImpl;
import jenkins.model.Jenkins;

import org.powermock.reflect.Whitebox;
import org.junit.runner.RunWith;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import com.amazonaws.services.ec2.model.Instance;

import java.util.ArrayList;
import java.util.HashSet;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import static hudson.plugins.ec2.EC2Cloud.DEFAULT_EC2_ENDPOINT;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
* Unit tests related to {@link AmazonEC2Cloud}, but do not require a Jenkins instance.
*/
@PowerMockIgnore({"javax.crypto.*", "org.hamcrest.*", "javax.net.ssl.*", "com.sun.org.apache.xerces.*", "javax.xml.*", "org.xml.*"})
@RunWith(PowerMockRunner.class)
@PrepareForTest({EC2Cloud.class, Jenkins.class})
public class AmazonEC2CloudUnitTest {

    @Test
    public void testEC2EndpointURLCreation() throws MalformedURLException {
        AmazonEC2Cloud.DescriptorImpl descriptor = new AmazonEC2Cloud.DescriptorImpl();

        assertEquals(new URL(DEFAULT_EC2_ENDPOINT), descriptor.determineEC2EndpointURL(null));
        assertEquals(new URL(DEFAULT_EC2_ENDPOINT), descriptor.determineEC2EndpointURL(""));
        assertEquals(new URL("https://www.abc.com"), descriptor.determineEC2EndpointURL("https://www.abc.com"));
    }

    @Test
    public void testInstaceCap() throws Exception {
        AmazonEC2Cloud cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1",
                                                    null, "key", null, Collections.emptyList(),
                                                    "roleArn", "roleSessionName");
        assertEquals(cloud.getInstanceCap(), Integer.MAX_VALUE);
        assertEquals(cloud.getInstanceCapStr(), "");

        final int cap = 3;
        final String capStr = String.valueOf(cap);
        cloud = new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1",
                                    null, "key", capStr, Collections.emptyList(),
                                    "roleArn", "roleSessionName");
        assertEquals(cloud.getInstanceCap(), cap);
        assertEquals(cloud.getInstanceCapStr(), capStr);
    }

    @Test
    public void testSpotInstanceCount() throws Exception {
        final int numberOfSpotInstanceRequests = 105;
        AmazonEC2Cloud cloud = PowerMockito.spy(new AmazonEC2Cloud("us-east-1", true, "abc", "us-east-1",
                                                    null, "key", null, Collections.emptyList(),
                                                    "roleArn", "roleSessionName"));
        PowerMockito.mockStatic(Jenkins.class);
        Jenkins jenkinsMock = mock(Jenkins.class);
        EC2SpotSlave spotSlaveMock = mock(EC2SpotSlave.class);

        PowerMockito.when(Jenkins.get()).thenReturn(jenkinsMock);
        PowerMockito.when(jenkinsMock.getNodes()).thenReturn(Collections.singletonList(spotSlaveMock));
        when(spotSlaveMock.getSpotRequest()).thenReturn(null);
        when(spotSlaveMock.getSpotInstanceRequestId()).thenReturn("sir-id");

        List<Instance> instances = new ArrayList<Instance>();
        for(int i=0; i<=numberOfSpotInstanceRequests; i++) {
            instances.add(new Instance().withInstanceId("id"+i).withTags(new Tag().withKey("jenkins_node_type").withValue("spot")));
        }
        
        AmazonEC2FactoryMockImpl.instances = instances;
        
        Mockito.doReturn(AmazonEC2FactoryMockImpl.createAmazonEC2Mock(null)).when(cloud).connect();

        Object[] params = {null, "jenkinsurl", new HashSet<String>()};
        int n = Whitebox.invokeMethod(cloud, "countCurrentEC2SpotSlaves", params);
        
        // Should equal number of spot instance requests + 1 for spot nodes not having a spot instance request
        assertEquals(numberOfSpotInstanceRequests+1, n);
    }
}
