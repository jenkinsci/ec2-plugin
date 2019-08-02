package hudson.plugins.ec2.util;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import jenkins.model.Jenkins;

import java.io.IOException;

public class CredentialUtils {
    /**
     * Add credentials to the Jenkins system credential store.
     *
     * The credentials will be added to the "global" domain.
     *
     * @param credentials Credentials to add.
     * @throws IOException When the change couldn't be persisted.
     * @throws CredentialStoreNotFoundException When the Jenkins system credential store cannot be found.
     */
    public static void addGlobalSystemCredentials(Credentials credentials)
            throws IOException, CredentialStoreNotFoundException {
        for (CredentialsStore credentialsStore : CredentialsProvider.lookupStores(Jenkins.get())) {
            if (credentialsStore instanceof SystemCredentialsProvider.StoreImpl) {
                credentialsStore.addCredentials(Domain.global(), credentials);
                return;
            }
        }

        throw new CredentialStoreNotFoundException("Jenkins system credential store not found");
    }
}
