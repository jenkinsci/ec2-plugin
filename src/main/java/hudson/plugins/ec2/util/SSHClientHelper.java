package hudson.plugins.ec2.util;

import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.ssh.verifiers.HostKey;
import hudson.plugins.ec2.ssh.verifiers.HostKeyHelper;
import java.io.IOException;
import java.util.List;

public final class SSHClientHelper {

    private static final SSHClientHelper INSTANCE = new SSHClientHelper();

    private SSHClientHelper() {}

    public static SSHClientHelper getInstance() {
        return INSTANCE;
    }

    /**
     * Return an ordered list of preferred host key algorithm names based on the stored {@link HostKey}
     * for this {@link EC2Computer}. Used by {@link SSHClientManager} to reorder KEX negotiation.
     *
     * @param computer return preferred algorithms for this computer.
     * @return an ordered list of algorithm name strings, or empty list if no preference.
     */
    public List<String> getPreferredAlgorithmNames(EC2Computer computer) {
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

        return switch (trustedAlgorithm) {
            case "ssh-rsa" -> List.of("ssh-rsa", "rsa-sha2-256", "rsa-sha2-512");
            case "ecdsa-sha2-nistp256" -> List.of("ecdsa-sha2-nistp256");
            case "ecdsa-sha2-nistp384" -> List.of("ecdsa-sha2-nistp384");
            case "ecdsa-sha2-nistp521" -> List.of("ecdsa-sha2-nistp521");
            case "ssh-ed25519" -> List.of("ssh-ed25519");
            default -> List.of();
        };
    }
}
