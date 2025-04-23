package hudson.plugins.ec2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.plugins.ec2.MockEC2Computer;
import hudson.plugins.ec2.ssh.verifiers.HostKeyHelper;
import java.security.Provider;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.signature.Signature;
import org.apache.sshd.common.util.security.AbstractSecurityProviderRegistrar;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.recipes.LocalData;
import org.mockito.Mockito;

@WithJenkins
class SSHClientHelperTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    @LocalData
    void setupSshClientWithNoHostKey() throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("noHostKey");

        try (SshClient client = SSHClientHelper.getInstance().setupSshClient(computer);
                SshClient defaultClient = SshClient.setUpDefaultClient()) {
            List<NamedFactory<Signature>> signatureFactories = client.getSignatureFactories();
            assertEquals(
                    defaultClient.getSignatureFactories(),
                    signatureFactories,
                    "No existing host key found, created client should not have customized signature factories");
        }
    }

    @Test
    @LocalData("ecdsaSha2Nistp256")
    void setupSshClientWithHostKeyECDSASha2Nistp256Supported() throws Exception {
        // don't run this if EC is not supported
        assumeTrue(SecurityUtils.isECCSupported());

        MockEC2Computer computer = MockEC2Computer.createComputer("HostKey");
        assertNotNull(HostKeyHelper.getInstance().getHostKey(computer), "Expected an HostKey file");

        List<BuiltinSignatures> expected = List.of(BuiltinSignatures.nistp256, BuiltinSignatures.nistp256_cert);
        String message =
                "Existing host key found and EC provider exists, created client should have customized signature factories";

        doTestPreferredAlgorithms(message, expected);
    }

    @Test
    @LocalData("ecdsaSha2Nistp384")
    void setupSshClientWithHostKeyECDSASha2Nistp384Supported() throws Exception {
        // don't run this if EC is not supported
        assumeTrue(SecurityUtils.isECCSupported());

        MockEC2Computer computer = MockEC2Computer.createComputer("HostKey");
        assertNotNull(HostKeyHelper.getInstance().getHostKey(computer), "Expected an HostKey file");

        List<BuiltinSignatures> expected = List.of(BuiltinSignatures.nistp384, BuiltinSignatures.nistp384_cert);
        String message =
                "Existing host key found and EC provider exists, created client should have customized signature factories";

        doTestPreferredAlgorithms(message, expected);
    }

    @Test
    @LocalData("ecdsaSha2Nistp521")
    void setupSshClientWithHostKeyECDSASha2Nistp521Supported() throws Exception {
        // don't run this if EC is not supported
        assumeTrue(SecurityUtils.isECCSupported());

        MockEC2Computer computer = MockEC2Computer.createComputer("HostKey");
        assertNotNull(HostKeyHelper.getInstance().getHostKey(computer), "Expected an HostKey file");

        List<BuiltinSignatures> expected = List.of(BuiltinSignatures.nistp521, BuiltinSignatures.nistp521_cert);
        String message =
                "Existing host key found and EC provider exists, created client should have customized signature factories";

        doTestPreferredAlgorithms(message, expected);
    }

    @Test
    @LocalData("ed25519")
    void setupSshClientWithHostKeyEDDSANotSupported() throws Exception {
        SecurityUtils.registerSecurityProvider(new MockEDDSASecurityProviderRegistrar(false));
        // don't run this if EDDSA is supported
        assumeFalse(SecurityUtils.isEDDSACurveSupported());

        MockEC2Computer computer = MockEC2Computer.createComputer("HostKey");
        assertNotNull(HostKeyHelper.getInstance().getHostKey(computer), "Expected an HostKey file");

        try (SshClient client = SSHClientHelper.getInstance().setupSshClient(computer);
                SshClient defaultClient = SshClient.setUpDefaultClient()) {
            List<NamedFactory<Signature>> signatureFactories = client.getSignatureFactories();
            assertEquals(
                    defaultClient.getSignatureFactories(),
                    signatureFactories,
                    "Existing host key found but no EDDSA provider, created client should not have customized signature factories");
        }
    }

    @Test
    @LocalData("ed25519")
    void setupSshClientWithHostKeyEDDSASupported() throws Exception {
        SecurityUtils.registerSecurityProvider(new MockEDDSASecurityProviderRegistrar(true));
        // don't run this if EDDSA is not supported
        assumeTrue(SecurityUtils.isEDDSACurveSupported());

        List<BuiltinSignatures> expected =
                List.of(BuiltinSignatures.ed25519, BuiltinSignatures.ed25519_cert, BuiltinSignatures.sk_ssh_ed25519);
        String message =
                "Existing host key found and EDDSA provider exists, created client should have customized signature factories";

        doTestPreferredAlgorithms(message, expected);
    }

    private static void doTestPreferredAlgorithms(String message, List<BuiltinSignatures> expected) throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("HostKey");
        assertNotNull(HostKeyHelper.getInstance().getHostKey(computer), "Expected an HostKey file");

        try (SshClient client = SSHClientHelper.getInstance().setupSshClient(computer)) {
            List<NamedFactory<Signature>> signatureFactories = client.getSignatureFactories();
            List<NamedFactory<Signature>> actual =
                    signatureFactories.stream().limit(expected.size()).collect(Collectors.toList());
            assertEquals(expected, actual, message);
        }
    }

    /**
     * This class registers a mocked security provider for EDDSA.
     */
    private static class MockEDDSASecurityProviderRegistrar extends AbstractSecurityProviderRegistrar {

        private final boolean isSupported;

        /**
         * @param isSupported if true, EDDSA is supported
         */
        public MockEDDSASecurityProviderRegistrar(boolean isSupported) {
            super(SecurityUtils.EDDSA);
            this.isSupported = isSupported;
        }

        @Override
        public boolean isSupported() {
            return isSupported;
        }

        @Override
        public Provider getSecurityProvider() {
            Provider mock = Mockito.mock(Provider.class);
            Mockito.when(mock.getName()).thenReturn(SecurityUtils.EDDSA);
            return mock;
        }
    }
}
