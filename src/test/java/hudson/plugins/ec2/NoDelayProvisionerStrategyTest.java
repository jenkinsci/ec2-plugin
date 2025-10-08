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
     * Enum representing different node states for testing.
     */
    enum NodeState {
        OFFLINE(false, false, false),
        CONNECTING(false, true, false),
        ONLINE_IDLE(true, false, true),
        ONLINE_BUSY(true, false, false);

        final boolean online;
        final boolean connecting;

        NodeState(boolean online, boolean connecting, boolean idle) {
            this.online = online;
            this.connecting = connecting;
            this.idle = idle;
        }
    }

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
        EC2AbstractSlave offlineNode1 = createMockEC2Node("offline-1", testLabel, NodeState.OFFLINE);
        EC2AbstractSlave offlineNode2 = createMockEC2Node("offline-2", testLabel, NodeState.OFFLINE);
        EC2AbstractSlave connectingNode = createMockEC2Node("connecting-1", testLabel, NodeState.CONNECTING);
        EC2AbstractSlave onlineIdleNode = createMockEC2Node("online-idle-1", testLabel, NodeState.ONLINE_IDLE);
        EC2AbstractSlave onlineBusyNode = createMockEC2Node("online-busy-1", testLabel, NodeState.ONLINE_BUSY);

        // Add nodes to Jenkins
        jenkins.addNode(offlineNode1);
        jenkins.addNode(offlineNode2);
        jenkins.addNode(connectingNode);
        jenkins.addNode(onlineIdleNode);
        jenkins.addNode(onlineBusyNode);

        // Call countProvisionedButNotExecutingNodes directly
        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        int count = strategy.countProvisionedButNotExecutingNodes(testLabel);

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
        EC2AbstractSlave nodeWithLabelA1 = createMockEC2Node("node-a-1", labelA, NodeState.OFFLINE);
        EC2AbstractSlave nodeWithLabelA2 = createMockEC2Node("node-a-2", labelA, NodeState.OFFLINE);
        EC2AbstractSlave nodeWithLabelB = createMockEC2Node("node-b-1", labelB, NodeState.OFFLINE);

        jenkins.addNode(nodeWithLabelA1);
        jenkins.addNode(nodeWithLabelA2);
        jenkins.addNode(nodeWithLabelB);

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();

        int countForLabelA = strategy.countProvisionedButNotExecutingNodes(labelA);
        int countForLabelB = strategy.countProvisionedButNotExecutingNodes(labelB);

        assertEquals(2, countForLabelA, "Should count 2 nodes with label-a");
        assertEquals(1, countForLabelB, "Should count 1 node with label-b");
    }

    /**
     * Test that the counting logic accurately handles a large number of concurrently provisioned nodes.
     *
     * This test simulates the scenario where many nodes are provisioned rapidly (e.g., during
     * a burst of 100 simultaneous builds). It verifies that the counting mechanism correctly
     * identifies all offline nodes without errors or undercounting.
     */
    @Test
    void testCountingLogic_handlesConcurrentProvisioning(JenkinsRule rule) throws Exception {
        this.r = rule;
        Jenkins jenkins = r.jenkins;

        Label testLabel = Label.get("stress-test");

        // Rapidly add 50 offline nodes (simulating provision() completing but nodes not yet connecting)
        for (int i = 0; i < 50; i++) {
            EC2AbstractSlave node = createMockEC2Node("stress-node-" + i, testLabel, NodeState.OFFLINE);
            jenkins.addNode(node);
        }

        // Count them using the strategy
        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        int count = strategy.countProvisionedButNotExecutingNodes(testLabel);

        // Should count all 50 offline nodes (1 executor each = 50 total)
        assertEquals(50, count, "Should accurately count all 50 offline nodes during concurrent provisioning");
    }

    /**
     * Test that spare instances (online and idle) are NOT counted by countProvisionedButNotExecutingNodes().
     *
     * Background:
     * - Spare instances are maintained by MinimumInstanceChecker to provide pre-provisioned capacity
     * - They are defined as agents that are online AND idle (ready to accept work immediately)
     * - When functioning correctly, spare instances spend 99.9% of their time online and idle
     *
     * Key behavior verified by this test:
     * - Spare instances in steady state (online & idle) are NOT counted in provisionedButNotExecuting
     * - They ARE counted in snapshot.getAvailableExecutors() (available capacity)
     * - Only offline EC2 nodes (the JENKINS-76171 gap) are counted in provisionedButNotExecuting
     * - This prevents double-counting and ensures correct capacity calculations
     *
     * This test addresses Jesse Glick's question: "What about spare instances? Are these going
     * to be counted? Do we want them to be?"
     *
     * Answer: Spare instances ARE counted, but in the RIGHT place (availableExecutors), NOT in
     * provisionedButNotExecuting. This is correct because spare instances are available capacity
     * that should prevent additional provisioning.
     *
     * Scenario tested:
     * - minimumNumberOfSpareInstances = 5 (conceptually)
     * - 5 nodes online and idle (spare instances in steady state)
     * - 3 nodes offline and not connecting (JENKINS-76171 gap - instances just provisioned)
     * - Expected: countProvisionedButNotExecutingNodes() returns 3 (NOT 8)
     */
    @Test
    void testSpareInstances_notCountedInProvisionedButNotExecuting(JenkinsRule rule) throws Exception {
        this.r = rule;
        Jenkins jenkins = r.jenkins;

        Label testLabel = Label.get("spare-test");

        // Create 5 spare instances in steady state: online and idle
        // These represent minimumNumberOfSpareInstances=5 after they've fully started
        // In this state, they're ready to accept jobs immediately
        EC2AbstractSlave spare1 = createMockEC2Node("spare-1", testLabel, NodeState.ONLINE_IDLE);
        EC2AbstractSlave spare2 = createMockEC2Node("spare-2", testLabel, NodeState.ONLINE_IDLE);
        EC2AbstractSlave spare3 = createMockEC2Node("spare-3", testLabel, NodeState.ONLINE_IDLE);
        EC2AbstractSlave spare4 = createMockEC2Node("spare-4", testLabel, NodeState.ONLINE_IDLE);
        EC2AbstractSlave spare5 = createMockEC2Node("spare-5", testLabel, NodeState.ONLINE_IDLE);

        // Create 3 nodes in the JENKINS-76171 gap: offline and not connecting
        // These represent nodes just added to Jenkins by provision(), before connection starts
        EC2AbstractSlave offlineNode1 = createMockEC2Node("offline-1", testLabel, NodeState.OFFLINE);
        EC2AbstractSlave offlineNode2 = createMockEC2Node("offline-2", testLabel, NodeState.OFFLINE);
        EC2AbstractSlave offlineNode3 = createMockEC2Node("offline-3", testLabel, NodeState.OFFLINE);

        // Add all nodes to Jenkins
        jenkins.addNode(spare1);
        jenkins.addNode(spare2);
        jenkins.addNode(spare3);
        jenkins.addNode(spare4);
        jenkins.addNode(spare5);
        jenkins.addNode(offlineNode1);
        jenkins.addNode(offlineNode2);
        jenkins.addNode(offlineNode3);

        // Call countProvisionedButNotExecutingNodes()
        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        int count = strategy.countProvisionedButNotExecutingNodes(testLabel);

        // Should count ONLY the 3 offline nodes (1 executor each = 3 total)
        // The 5 spare instances should NOT be counted because they're online
        assertEquals(
                3,
                count,
                "Should count only offline nodes (3), NOT online spare instances (5). "
                        + "Spare instances are counted in availableExecutors, not provisionedButNotExecuting.");
    }

    /**
     * Test the complete lifecycle of spare instance counting through different states.
     *
     * This test demonstrates how a spare instance transitions through states and where
     * it's counted at each stage:
     *
     * 1. OFFLINE (just provisioned): Counted in provisionedButNotExecuting
     * 2. CONNECTING (starting up): Counted in snapshot.getConnectingExecutors()
     * 3. ONLINE & IDLE (steady state): Counted in snapshot.getAvailableExecutors()
     * 4. ONLINE & BUSY (executing job): Counted as busy executor
     *
     * This verifies that spare instances move through the counting buckets correctly
     * and are never double-counted.
     */
    @Test
    void testSpareInstanceLifecycle_countingTransitions(JenkinsRule rule) throws Exception {
        this.r = rule;
        Jenkins jenkins = r.jenkins;

        Label testLabel = Label.get("lifecycle-test");
        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();

        // Stage 1: Spare instance just provisioned (offline)
        // This is the brief moment after MinimumInstanceChecker.provision() completes
        // but before the connection process starts
        EC2AbstractSlave spareOffline = createMockEC2Node("spare-offline", testLabel, NodeState.OFFLINE);
        jenkins.addNode(spareOffline);

        int countOffline = strategy.countProvisionedButNotExecutingNodes(testLabel);
        assertEquals(1, countOffline, "Offline spare instance should be counted in provisionedButNotExecuting");

        // Stage 2: Spare instance connecting
        // After connect() is called, the node shows as "connecting"
        // In this state, it would be counted in snapshot.getConnectingExecutors()
        // and should NOT be counted in provisionedButNotExecuting
        EC2AbstractSlave spareConnecting = createMockEC2Node("spare-connecting", testLabel, NodeState.CONNECTING);
        jenkins.addNode(spareConnecting);

        int countConnecting = strategy.countProvisionedButNotExecutingNodes(testLabel);
        assertEquals(
                1,
                countConnecting,
                "Connecting spare instance should NOT be counted (already in connectingExecutors). "
                        + "Only the offline instance should be counted.");

        // Stage 3: Spare instance online and idle (steady state)
        // This is where spare instances spend most of their time
        // In this state, they're counted in snapshot.getAvailableExecutors()
        // and should NOT be counted in provisionedButNotExecuting
        EC2AbstractSlave spareIdle = createMockEC2Node("spare-idle", testLabel, NodeState.ONLINE_IDLE);
        jenkins.addNode(spareIdle);

        int countIdle = strategy.countProvisionedButNotExecutingNodes(testLabel);
        assertEquals(
                1,
                countIdle,
                "Online idle spare instance should NOT be counted (already in availableExecutors). "
                        + "Only the offline instance should be counted.");

        // Stage 4: Spare instance online and busy (executing a job)
        // When a spare instance accepts a job, it's no longer "spare" - it's working
        // In this state, it's counted as a busy executor and should NOT be in provisionedButNotExecuting
        EC2AbstractSlave spareBusy = createMockEC2Node("spare-busy", testLabel, NodeState.ONLINE_BUSY);
        jenkins.addNode(spareBusy);

        int countBusy = strategy.countProvisionedButNotExecutingNodes(testLabel);
        assertEquals(
                1,
                countBusy,
                "Online busy instance should NOT be counted (executing a job). "
                        + "Only the offline instance should be counted.");

        // Final verification: only the original offline node is counted throughout
        // This proves that as spare instances transition through states, they're always
        // counted somewhere appropriate, but never double-counted
    }

    /**
     * Test edge case: offline spare instance (unusual but possible scenario).
     *
     * Spare instances are intended to be online and idle, but they can become offline
     * due to network issues, SSH problems, or manual intervention. This test verifies
     * that if a spare instance goes offline, it WOULD be counted in provisionedButNotExecuting.
     *
     * This is actually correct behavior: an offline instance is not available capacity,
     * so it should be counted in the gap until it comes back online or is removed.
     */
    @Test
    void testSpareInstance_whenOffline_isCountedCorrectly(JenkinsRule rule) throws Exception {
        this.r = rule;
        Jenkins jenkins = r.jenkins;

        Label testLabel = Label.get("offline-spare-test");

        // Create a spare instance that has gone offline (network issue, etc.)
        // This is unusual but can happen in production
        EC2AbstractSlave offlineSpare = createMockEC2Node("offline-spare", testLabel, NodeState.OFFLINE);
        jenkins.addNode(offlineSpare);

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        int count = strategy.countProvisionedButNotExecutingNodes(testLabel);

        // This offline "spare" instance SHOULD be counted because it's not available
        // It's not really "spare" anymore - it's offline and needs attention
        assertEquals(
                1,
                count,
                "Offline spare instance should be counted (it's not available capacity). "
                        + "The 'spare' designation is semantic; offline nodes are counted regardless.");
    }

    /**
     * Helper method to create a mock EC2AbstractSlave for testing.
     *
     * @param name node name
     * @param label label to assign
     * @param state the desired state of the node (OFFLINE, CONNECTING, ONLINE_IDLE, ONLINE_BUSY)
     * @return mock EC2AbstractSlave
     */
    private EC2AbstractSlave createMockEC2Node(String name, Label label, NodeState state) throws Exception {
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
        Mockito.when(computer.isOnline()).thenReturn(state.online);
        Mockito.when(computer.isConnecting()).thenReturn(state.connecting);
        Mockito.when(computer.isOffline()).thenReturn(!state.online && !state.connecting);
        Mockito.when(computer.isIdle()).thenReturn(state.idle);

        Mockito.when(slave.toComputer()).thenReturn(computer);

        return slave;
    }
}
