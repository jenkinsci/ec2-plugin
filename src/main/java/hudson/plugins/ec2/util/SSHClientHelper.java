package hudson.plugins.ec2.util;

import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.ssh.verifiers.HostKey;
import hudson.plugins.ec2.ssh.verifiers.HostKeyHelper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.signature.Signature;

public final class SSHClientHelper {

    private static final SSHClientHelper INSTANCE = new SSHClientHelper();

    private SSHClientHelper() {}

    public static SSHClientHelper getInstance() {
        return INSTANCE;
    }

    /**
     * Set up an SSH client configured for the given {@link EC2Computer}.
     *
     * @param computer the {@link EC2Computer} the created client will connect to
     * @return an SSH client configured for this {@link EC2Computer}
     */
    public SshClient setupSshClient(EC2Computer computer) {
        SshClient client = SshClient.setUpDefaultClient();

        List<BuiltinSignatures> preferred = getPreferredSignatures(computer);
        if (!preferred.isEmpty()) {
            LinkedHashSet<NamedFactory<Signature>> signatureFactoriesSet = new LinkedHashSet<>(preferred);
            signatureFactoriesSet.addAll(client.getSignatureFactories());
            client.setSignatureFactories(new ArrayList<>(signatureFactoriesSet));
        }

        return client;
    }

    /**
     * Return an ordered list of signature algorithms that should be used. Noticeably, if a {@link HostKey} already exists for this
     * {@link EC2Computer}, the {@link HostKey} algorithm will be attempted first.
     *
     * @param computer return a list of signature for this computer.
     * @return an ordered list of signature algorithms that should be used.
     */
    public List<BuiltinSignatures> getPreferredSignatures(EC2Computer computer) {
        String trustedAlgorithm;
        try {
            HostKey trustedHostKey = HostKeyHelper.getInstance().getHostKey(computer);
            if (trustedHostKey == null) {
                return List.of();
            }
            trustedAlgorithm = trustedHostKey.getAlgorithm();
        } catch (IOException e) {
            return List.of();
        }

        List<BuiltinSignatures> preferred;
        switch (trustedAlgorithm) {
            case "ssh-rsa":
                preferred = List.of(
                        BuiltinSignatures.rsa,
                        BuiltinSignatures.rsaSHA256,
                        BuiltinSignatures.rsaSHA256_cert,
                        BuiltinSignatures.rsaSHA512,
                        BuiltinSignatures.rsaSHA512_cert);
                break;
            case "ecdsa-sha2-nistp256":
                preferred = List.of(BuiltinSignatures.nistp256, BuiltinSignatures.nistp256_cert);
                break;
            case "ecdsa-sha2-nistp384":
                preferred = List.of(BuiltinSignatures.nistp384, BuiltinSignatures.nistp384_cert);
                break;
            case "ecdsa-sha2-nistp521":
                preferred = List.of(BuiltinSignatures.nistp521, BuiltinSignatures.nistp521_cert);
                break;
            case "ssh-ed25519":
                preferred = List.of(
                        BuiltinSignatures.ed25519, BuiltinSignatures.ed25519_cert, BuiltinSignatures.sk_ssh_ed25519);
                break;
            default:
                return List.of();
        }

        // Keep only supported algorithms
        return NamedFactory.setUpBuiltinFactories(true, preferred);
    }
}
