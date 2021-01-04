package hudson.plugins.ec2.util;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.util.Secret;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.List;

/**
 * Use an instance of this class alongside an instance of {@see BasicSSHUserPrivateKey} to ensure that the plugin can
 * use ANY {@see SSHUserPrivateKey} subtype, not just a particular subtype.
 */
public class TestSSHUserPrivateKey extends BaseStandardCredentials implements SSHUserPrivateKey {

    private final String username;
    private final Secret passphrase;
    BasicSSHUserPrivateKey.PrivateKeySource privateKeySource;

    public TestSSHUserPrivateKey(CredentialsScope scope,
                                 String id,
                                 String username,
                                 BasicSSHUserPrivateKey.PrivateKeySource privateKeySource,
                                 String passphrase,
                                 String description) {
        super(scope, id, description);
        this.username = username;
        this.privateKeySource = privateKeySource;
        this.passphrase = Secret.fromString(passphrase);
    }

    @NonNull
    @Override
    public String getPrivateKey() {
        List<String> privateKeys = getPrivateKeys();
        return privateKeys.isEmpty() ? "" : privateKeys.get(0);
    }

    @Override
    public Secret getPassphrase() {
        return passphrase;
    }

    @NonNull
    @Override
    public List<String> getPrivateKeys() {
        return privateKeySource.getPrivateKeys();
    }

    @NonNull
    @Override
    public String getUsername() {
        return username;
    }
}
