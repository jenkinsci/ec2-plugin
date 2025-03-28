package hudson.plugins.ec2.util;

import java.util.Objects;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;

public class KeyPair {
    private final KeyPairInfo keyPairInfo;
    private final String material;

    public KeyPair(KeyPairInfo keyPairInfo, String material) {
        this.keyPairInfo = Objects.requireNonNull(keyPairInfo);
        this.material = Objects.requireNonNull(material);
    }

    public KeyPairInfo getKeyPairInfo() {
        return keyPairInfo;
    }

    public String getMaterial() {
        return material;
    }
}
