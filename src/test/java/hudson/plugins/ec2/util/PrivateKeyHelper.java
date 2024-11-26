package hudson.plugins.ec2.util;

import java.io.IOException;
import java.io.StringWriter;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

public class PrivateKeyHelper {

    public static String generate() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(4096);
            StringWriter stringWriter = new StringWriter(4096);
            JcaPEMWriter jcaPEMWriter = new JcaPEMWriter(stringWriter);
            PrivateKey privateKey = keyPairGenerator.generateKeyPair().getPrivate();
            jcaPEMWriter.writeObject(privateKey);
            jcaPEMWriter.flush();
            return stringWriter.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            // It's fine to just wrap in a runtime exception. This is only used in tests.
            throw new IllegalStateException(e);
        }
    }
}
