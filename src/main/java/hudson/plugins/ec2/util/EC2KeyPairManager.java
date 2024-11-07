package hudson.plugins.ec2.util;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairResult;
import com.amazonaws.services.ec2.model.DeleteKeyPairRequest;
import com.amazonaws.services.ec2.model.KeyPair;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.util.Secret;

import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Logger;

@Extension
public class EC2KeyPairManager {
    private HashMap<String, Secret> vault;
    private static final Logger LOGGER = Logger.getLogger(EC2KeyPairManager.class.getName());
    private EC2Cloud cloud;

    public EC2KeyPairManager() {
        this.vault = new HashMap<>();
    }

    public void setCloud(EC2Cloud cloud) {
        this.cloud = cloud;
    }

    public String createKeyPair(@NonNull AmazonEC2 ec2) throws AmazonClientException {
        LOGGER.fine(() -> "begin create keypair request");
        CreateKeyPairResult result = ec2.createKeyPair(new CreateKeyPairRequest("dev-1-" + UUID.randomUUID()));
        if ((result.getKeyPair().getKeyMaterial() == null) || (result.getKeyPair().getKeyMaterial().isBlank())) {
            LOGGER.warning(() -> "There was a problem creating a new keypair, resulting key was null or empty");
            throw new AmazonClientException("There was an error creating a new keypair, resulting key was null or empty!");
        }
        LOGGER.fine("new keypair created with name: " + result.getKeyPair().getKeyName());
        vault.put(result.getKeyPair().getKeyName(), Secret.fromString(result.getKeyPair().getKeyMaterial()));
        return result.getKeyPair().getKeyName();
    }

    public void deleteKeyPair(@NonNull AmazonEC2 ec2, @NonNull String keyPairName) {
        LOGGER.fine(() -> "Delete keypair request for keypair: " + keyPairName);
        if (keyPairName != null && !keyPairName.isEmpty()) {
            ec2.deleteKeyPair(new DeleteKeyPairRequest(keyPairName));
        } else {
            LOGGER.warning(() -> "Delete keypair request for empty keyname, maybe the instance was invalid?");
        }
    }

    public String getPrivateKey(@NonNull String keyPairName) {
        LOGGER.fine("finding keypair for keyPairName " + keyPairName);
        dumpKeyNames();
        return (this.vault.get(keyPairName) != null) ? this.vault.get(keyPairName).getPlainText() : null;
    }

    private void dumpKeyNames() {
        LOGGER.fine(() -> "Vault contains the following keys");
        vault.keySet().stream().forEach(key -> LOGGER.fine("KeyName: " + key));
    }
}
