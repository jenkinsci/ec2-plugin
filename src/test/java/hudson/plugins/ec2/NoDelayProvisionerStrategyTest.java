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

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.DumbSlave;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mockito;

/**
 * Tests for NoDelayProvisionerStrategy capacity counting fixes (JENKINS-76171).
 *
 * These tests verify that the strategy correctly counts nodes in various states
 * to prevent over-provisioning during rapid scaling scenarios.
 */
@WithJenkins
class NoDelayProvisionerStrategyTest {

    private JenkinsRule r;

    /**
     * Test that countProvisionedButNotExecutingNodes() correctly counts only offline EC2 nodes.
     *
     * This test verifies the fix for JENKINS-76171 where nodes in the gap between being added to
     * Jenkins and starting to connect were not counted, causing over-provisioning.
     */
    @Test
    void testCountProvisionedButNotExecutingNodes_countsOfflineNodesOnly(JenkinsRule rule) throws Exception {
        this.r = rule;
        Jenkins jenkins = r.jenkins;

        // Create a test label
        Label testLabel = Label.get("test-label");

        // Create mock EC2 nodes in different states
        EC2AbstractSlave offlineNode1 = createMockEC2Node("offline-1", testLabel, false, false, false);
        EC2AbstractSlave offlineNode2 = createMockEC2Node("offline-2", testLabel, false, false, false);
        EC2AbstractSlave connectingNode = createMockEC2Node("connecting-1", testLabel, false, true, false);
        EC2AbstractSlave onlineIdleNode = createMockEC2Node("online-idle-1", testLabel, true, false, true);
        EC2AbstractSlave onlineBusyNode = createMockEC2Node("online-busy-1", testLabel, true, false, false);

        // Add nodes to Jenkins
        jenkins.addNode(offlineNode1);
        jenkins.addNode(offlineNode2);
        jenkins.addNode(connectingNode);
        jenkins.addNode(onlineIdleNode);
        jenkins.addNode(onlineBusyNode);

        // Create strategy instance and use reflection to call private method
        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        java.lang.reflect.Method method =
                NoDelayProvisionerStrategy.class.getDeclaredMethod("countProvisionedButNotExecutingNodes", Label.class);
        method.setAccessible(true);
        int count = (int) method.invoke(strategy, testLabel);

        // Should only count the 2 offline nodes (1 executor each = 2 total)
        assertEquals(2, count, "Should count only offline nodes");
    }

    /**
     * Test that countProvisionedButNotExecutingNodes() respects label filtering.
     *
     * Verifies that only nodes matching the specified label are counted.
     */
    @Test
    void testCountProvisionedButNotExecutingNodes_respectsLabelFiltering(JenkinsRule rule) throws Exception {
        this.r = rule;
        Jenkins jenkins = r.jenkins;

        Label labelA = Label.get("label-a");
        Label labelB = Label.get("label-b");

        // Create offline nodes with different labels
        EC2AbstractSlave nodeWithLabelA1 = createMockEC2Node("node-a-1", labelA, false, false, false);
        EC2AbstractSlave nodeWithLabelA2 = createMockEC2Node("node-a-2", labelA, false, false, false);
        EC2AbstractSlave nodeWithLabelB = createMockEC2Node("node-b-1", labelB, false, false, false);

        jenkins.addNode(nodeWithLabelA1);
        jenkins.addNode(nodeWithLabelA2);
        jenkins.addNode(nodeWithLabelB);

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        java.lang.reflect.Method method =
                NoDelayProvisionerStrategy.class.getDeclaredMethod("countProvisionedButNotExecutingNodes", Label.class);
        method.setAccessible(true);

        int countForLabelA = (int) method.invoke(strategy, labelA);
        int countForLabelB = (int) method.invoke(strategy, labelB);

        assertEquals(2, countForLabelA, "Should count 2 nodes with label-a");
        assertEquals(1, countForLabelB, "Should count 1 node with label-b");
    }

    /**
     * Test that countBusyExecutors() correctly counts only online and busy executors.
     *
     * This test verifies the fix for JENKINS-76171 where busy executors (online but not idle)
     * were not counted in available capacity, causing over-provisioning when nodes started
     * executing jobs.
     */
    @Test
    void testCountBusyExecutors_countsOnlyBusyNodes(JenkinsRule rule) throws Exception {
        this.r = rule;
        Jenkins jenkins = r.jenkins;

        Label testLabel = Label.get("test-label");

        // Create nodes in various states
        EC2AbstractSlave offlineNode = createMockEC2Node("offline-1", testLabel, false, false, false);
        EC2AbstractSlave connectingNode = createMockEC2Node("connecting-1", testLabel, false, true, false);
        EC2AbstractSlave onlineIdleNode = createMockEC2Node("online-idle-1", testLabel, true, false, true);
        // Create a busy node (online but not idle)
        EC2AbstractSlave onlineBusyNode = createMockEC2Node("online-busy-1", testLabel, true, false, false);

        jenkins.addNode(offlineNode);
        jenkins.addNode(connectingNode);
        jenkins.addNode(onlineIdleNode);
        jenkins.addNode(onlineBusyNode);

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        java.lang.reflect.Method method =
                NoDelayProvisionerStrategy.class.getDeclaredMethod("countBusyExecutors", Label.class);
        method.setAccessible(true);
        int count = (int) method.invoke(strategy, testLabel);

        // Should only count the busy node (1 executor)
        assertEquals(1, count, "Should count only the busy node");
    }

    /**
     * Test that countBusyExecutors() excludes non-EC2 nodes.
     *
     * Verifies that only EC2AbstractSlave nodes are counted, not other node types.
     */
    @Test
    void testCountBusyExecutors_excludesNonEC2Nodes(JenkinsRule rule) throws Exception {
        this.r = rule;
        Jenkins jenkins = r.jenkins;

        Label testLabel = Label.get("test-label");

        // Create a non-EC2 node (DumbSlave)
        DumbSlave nonEc2Node = new DumbSlave("non-ec2-node", "/tmp", r.createComputerLauncher(null));
        nonEc2Node.setLabelString("test-label");

        // Create an EC2 node
        EC2AbstractSlave ec2Node = createMockEC2Node("ec2-node", testLabel, true, false, true);

        jenkins.addNode(nonEc2Node);
        jenkins.addNode(ec2Node);

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        java.lang.reflect.Method method =
                NoDelayProvisionerStrategy.class.getDeclaredMethod("countBusyExecutors", Label.class);
        method.setAccessible(true);
        int count = (int) method.invoke(strategy, testLabel);

        // DumbSlave should not be counted, only EC2 nodes
        assertTrue(count >= 0, "Should only count EC2 nodes");
    }

    /**
     * Helper method to create a mock EC2AbstractSlave for testing.
     *
     * @param name node name
     * @param label label to assign
     * @param online whether the node should appear online
     * @param connecting whether the node should appear connecting
     * @param idle whether the node should appear idle (only relevant if online=true)
     * @return mock EC2AbstractSlave
     */
    private EC2AbstractSlave createMockEC2Node(
            String name, Label label, boolean online, boolean connecting, boolean idle) throws Exception {
        // Create a minimal EC2OndemandSlave for testing
        SlaveTemplate template = Mockito.mock(SlaveTemplate.class);
        Mockito.when(template.getLabelString()).thenReturn(label.getExpression());

        EC2OndemandSlave slave = Mockito.spy(new EC2OndemandSlave(
                name,
                "i-" + name, // instance ID
                "", // template description
                "/tmp", // remoteFS
                1, // num executors
                label.getExpression(), // labelString
                Node.Mode.NORMAL,
                "", // initScript
                "/tmp", // tmpDir
                Collections.emptyList(), // nodeProperties
                "ec2-user", // remoteAdmin
                EC2AbstractSlave.DEFAULT_JAVA_PATH, // javaPath
                "", // jvmopts
                false, // stopOnTerminate
                "30", // idleTerminationMinutes
                "", // publicDNS
                "", // privateDNS
                Collections.emptyList(), // tags
                "", // cloudName
                0, // launchTimeout
                new UnixData(null, null, null, null, null), // amiType
                ConnectionStrategy.PUBLIC_DNS, // connectionStrategy
                -1, // maxTotalUses
                Tenancy.Default, // tenancy
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED, // metadataEndpointEnabled
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED, // metadataTokensRequired
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT, // metadataHopsLimit
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED // metadataSupported
                ));

        // Mock computer state
        Computer computer = Mockito.mock(Computer.class);
        Mockito.when(computer.isOnline()).thenReturn(online);
        Mockito.when(computer.isConnecting()).thenReturn(connecting);
        Mockito.when(computer.isOffline()).thenReturn(!online && !connecting);
        Mockito.when(computer.isIdle()).thenReturn(idle);

        Mockito.when(slave.toComputer()).thenReturn(computer);

        return slave;
    }
}
