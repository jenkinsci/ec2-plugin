package hudson.plugins.ec2.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.StringReader;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECField;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.InvalidKeySpecException;
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
public abstract class KeyHelper {
    private KeyHelper() {}

    /**
     * Decodes a PEM-encoded key pair into a {@link KeyPair} object. This method supports
     * various types of PEM input such as encrypted private keys, public keys, and key pairs.
     *
     * @param pem The PEM-formatted string containing the key data.
     * @param password The password used to decrypt encrypted key pairs, if applicable. Can be null if no password is required.
     * @return A {@link KeyPair} containing the public and private keys. If a public key is provided without a matching private key,
     *         the private key in the returned {@link KeyPair} will be null.
     * @throws IOException If an error occurs during parsing or decryption of the PEM input.
     * @throws IllegalArgumentException If the provided PEM input cannot be parsed or is of an unsupported type.
     */
    public static KeyPair decodeKeyPair(@NonNull String pem, @NonNull String password) throws IOException {
        try (org.bouncycastle.openssl.PEMParser pemParser =
                new org.bouncycastle.openssl.PEMParser(new StringReader(pem))) {
            Object object = pemParser.readObject();
            if (object == null) {
                throw new IllegalArgumentException("Failed to parse PEM input");
            }
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

    private static PublicKey generatePublicKeyFromPrivateKey(@NonNull PrivateKey privateKey) {
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
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return null;
        }
    }

    /**
     * Determines the SSH algorithm identifier corresponding to the given server public key.
     * This method matches the key type to the appropriate SSH algorithm string.
     * When an {@link ECPublicKey} is given, an NIST curse will be assumed.
     *
     * @param serverKey The server's {@link PublicKey} object for which the SSH algorithm identifier
     *                  needs to be determined.
     * @return A {@code String} representing the SSH algorithm identifier for the given server key,
     *         or {@code null} if the key type is unsupported or cannot be determined.
     */
    public static String getSshAlgorithm(@NonNull PublicKey serverKey) {
        switch (serverKey.getAlgorithm()) {
            case "RSA":
                return "ssh-rsa";
            case "EC":
                if (serverKey instanceof ECPublicKey) {
                    ECPublicKey ecPublicKey = (ECPublicKey) serverKey;
                    ECParameterSpec params = ecPublicKey.getParams();
                    if (params != null) {

                        EllipticCurve curve = params.getCurve();
                        ECField field = (curve != null) ? curve.getField() : null;
                        if (field != null) {
                            int fieldSize = field.getFieldSize();
                            // Assume NIST curve
                            return "ecdsa-sha2-nistp" + fieldSize;
                        }
                    }
                }
                return null;
            case "EdDSA":
                return "ssh-ed25519";
            default:
                return null;
        }
    }
}
