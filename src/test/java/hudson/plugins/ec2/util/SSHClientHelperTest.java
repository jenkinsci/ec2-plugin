package hudson.plugins.ec2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.plugins.ec2.MockEC2Computer;
import hudson.plugins.ec2.ssh.verifiers.HostKeyHelper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.recipes.LocalData;

@WithJenkins
class SSHClientHelperTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    @LocalData
    void noPreferredAlgorithmsWithNoHostKey() throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("noHostKey");
        List<String> algorithms = SSHClientHelper.getInstance().getPreferredAlgorithmNames(computer);
        assertTrue(algorithms.isEmpty(), "No host key should yield empty preferred algorithms");
    }

    @Test
    @LocalData("ecdsaSha2Nistp256")
    void preferredAlgorithmsForEcdsaNistp256() throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("HostKey");
        assertNotNull(HostKeyHelper.getInstance().getHostKey(computer), "Expected an HostKey file");

        List<String> algorithms = SSHClientHelper.getInstance().getPreferredAlgorithmNames(computer);
        assertEquals(List.of("ecdsa-sha2-nistp256"), algorithms);
    }

    @Test
    @LocalData("ecdsaSha2Nistp384")
    void preferredAlgorithmsForEcdsaNistp384() throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("HostKey");
        assertNotNull(HostKeyHelper.getInstance().getHostKey(computer), "Expected an HostKey file");

        List<String> algorithms = SSHClientHelper.getInstance().getPreferredAlgorithmNames(computer);
        assertEquals(List.of("ecdsa-sha2-nistp384"), algorithms);
    }

    @Test
    @LocalData("ecdsaSha2Nistp521")
    void preferredAlgorithmsForEcdsaNistp521() throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("HostKey");
        assertNotNull(HostKeyHelper.getInstance().getHostKey(computer), "Expected an HostKey file");

        List<String> algorithms = SSHClientHelper.getInstance().getPreferredAlgorithmNames(computer);
        assertEquals(List.of("ecdsa-sha2-nistp521"), algorithms);
    }

    @Test
    @LocalData("ed25519")
    void preferredAlgorithmsForEd25519() throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("HostKey");
        assertNotNull(HostKeyHelper.getInstance().getHostKey(computer), "Expected an HostKey file");

        List<String> algorithms = SSHClientHelper.getInstance().getPreferredAlgorithmNames(computer);
        assertEquals(List.of("ssh-ed25519"), algorithms);
    }
}
