package hudson.plugins.ec2.util;

import java.io.IOException;
import java.io.StringReader;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;

/**
 * Utility class to parse PEM.
 */
public abstract class PEMParser {
    private PEMParser() {}

    public static KeyPair decodeKeyPair(String pem, String password) throws IOException {
        try (org.bouncycastle.openssl.PEMParser pemParser =
                new org.bouncycastle.openssl.PEMParser(new StringReader(pem))) {
            Object object = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

            if (object instanceof PEMEncryptedKeyPair) {
                PEMKeyPair decryptedKeyPair = ((PEMEncryptedKeyPair) object)
                        .decryptKeyPair(new JcePEMDecryptorProviderBuilder().build(password.toCharArray()));
                PrivateKey privateKey = converter.getPrivateKey(decryptedKeyPair.getPrivateKeyInfo());
                PublicKey publicKey = converter.getPublicKey(decryptedKeyPair.getPublicKeyInfo());
                return new KeyPair(publicKey, privateKey);
            } else if (object instanceof PrivateKeyInfo) {
                PrivateKey privateKey = converter.getPrivateKey((PrivateKeyInfo) object);
                PublicKey publicKey = generatePublicKeyFromPrivateKey(privateKey);
                return new KeyPair(publicKey, privateKey);
            } else if (object instanceof SubjectPublicKeyInfo) {
                PublicKey publicKey = converter.getPublicKey((SubjectPublicKeyInfo) object);
                return new KeyPair(publicKey, null);
            } else if (object instanceof PEMKeyPair) {
                SubjectPublicKeyInfo publicKeyInfo = ((PEMKeyPair) object).getPublicKeyInfo();
                PrivateKeyInfo privateKeyInfo = ((PEMKeyPair) object).getPrivateKeyInfo();
                return new KeyPair(converter.getPublicKey(publicKeyInfo), converter.getPrivateKey(privateKeyInfo));
            } else {
                throw new IllegalArgumentException(
                        "Unsupported PEM object type: " + object.getClass().getName());
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse PEM input", e);
        }
    }

    private static PublicKey generatePublicKeyFromPrivateKey(PrivateKey privateKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(privateKey.getAlgorithm());

            if ("RSA".equalsIgnoreCase(privateKey.getAlgorithm())) {
                RSAPrivateCrtKeySpec rsaPrivateCrtKeySpec =
                        keyFactory.getKeySpec(privateKey, RSAPrivateCrtKeySpec.class);
                return keyFactory.generatePublic(rsaPrivateCrtKeySpec);
            } else if ("EC".equalsIgnoreCase(privateKey.getAlgorithm())) {
                ECPrivateKeySpec ecPrivateKeySpec = keyFactory.getKeySpec(privateKey, ECPrivateKeySpec.class);
                return keyFactory.generatePublic(ecPrivateKeySpec);
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
