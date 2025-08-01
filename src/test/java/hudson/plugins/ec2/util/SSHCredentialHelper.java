package hudson.plugins.ec2.util;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import java.io.IOException;
import jenkins.model.Jenkins;

public class SSHCredentialHelper {

    public static void assureSshCredentialAvailableThroughCredentialProviders(String id) {
        BasicSSHUserPrivateKey sshKeyCredentials = new BasicSSHUserPrivateKey(
                CredentialsScope.SYSTEM,
                id,
                "key",
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(PrivateKeyHelper.generate()),
                "",
                "EC2 Testing Cloud Private Key");

        addNewGlobalCredential(sshKeyCredentials);
    }

    private static void addNewGlobalCredential(Credentials credentials) {
        for (CredentialsStore credentialsStore : CredentialsProvider.lookupStores(Jenkins.get())) {

            if (credentialsStore instanceof SystemCredentialsProvider.StoreImpl) {

                try {
                    credentialsStore.addCredentials(Domain.global(), credentials);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to add testing credential");
                }
            }
        }
    }
}
