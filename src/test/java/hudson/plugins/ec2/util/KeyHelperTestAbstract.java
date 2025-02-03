package hudson.plugins.ec2.util;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class KeyHelperTestAbstract {
    static {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    public static KeyPair generateECKey(String curveName) throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        ECGenParameterSpec ecSpec = new ECGenParameterSpec(curveName);
        keyPairGenerator.initialize(ecSpec);
        return keyPairGenerator.generateKeyPair();
    }

    public static KeyPair generateRSAKey(int size) throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyPairGenerator.initialize(size);
        return keyPairGenerator.generateKeyPair();
    }

    public static KeyPair generateEdDSAKey() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EdDSA", BouncyCastleProvider.PROVIDER_NAME);
        keyPairGenerator.initialize(255);
        return keyPairGenerator.generateKeyPair();
    }
}
