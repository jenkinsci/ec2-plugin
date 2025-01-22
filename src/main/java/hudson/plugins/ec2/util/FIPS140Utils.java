package hudson.plugins.ec2.util;

import com.trilead.ssh2.signature.KeyAlgorithm;
import com.trilead.ssh2.signature.KeyAlgorithmManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.plugins.ec2.Messages;
import java.io.IOException;
import java.net.URL;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.interfaces.DSAKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import jenkins.bouncycastle.api.PEMEncodable;
import jenkins.security.FIPS140;
import org.apache.commons.lang.StringUtils;

/**
 * FIPS related utility methods (check Private and Public keys, ...)
 */
public class FIPS140Utils {

    /**
     * Checks if the key is allowed when FIPS mode is requested.
     * Allowed key with the following algorithms and sizes:
     * <ul>
     *     <li>DSA with key size >= 2048</li>
     *     <li>RSA with key size >= 2048</li>
     *     <li>Elliptic curve (ED25519) with field size >= 224</li>
     * </ul>
     * If the key is valid and allowed or not in FIPS mode method will just exit.
     * If not it will throw an {@link IllegalArgumentException}.
     * @param key The key to check.
     */
    public static void ensureKeyInFipsMode(Key key) {
        if (!FIPS140.useCompliantAlgorithms()) {
            return;
        }
        try {
            if (key instanceof RSAKey) {
                if (((RSAKey) key).getModulus().bitLength() < 2048) {
                    throw new IllegalArgumentException(Messages.AmazonEC2Cloud_invalidKeySizeInFIPSMode());
                }
            } else if (key instanceof DSAKey) {
                if (((DSAKey) key).getParams().getP().bitLength() < 2048) {
                    throw new IllegalArgumentException(Messages.AmazonEC2Cloud_invalidKeySizeInFIPSMode());
                }
            } else if (key instanceof ECKey) {
                if (((ECKey) key).getParams().getCurve().getField().getFieldSize() < 224) {
                    throw new IllegalArgumentException(Messages.AmazonEC2Cloud_invalidKeySizeECInFIPSMode());
                }
            } else {
                throw new IllegalArgumentException(
                        Messages.AmazonEC2Cloud_keyIsNotApprovedInFIPSMode(key.getAlgorithm()));
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Password leak prevention when FIPS mode is requested. If FIPS mode is not requested, this method does nothing.
     * Otherwise, ensure that no password can be leaked
     * @param url the requested URL
     * @param password the password used
     * @throws IllegalArgumentException if there is a risk that the password will leak
     */
    public static void ensureNoPasswordLeak(URL url, String password) {
        ensureNoPasswordLeak("https".equals(url.getProtocol()), password);
    }

    /**
     * Password leak prevention when FIPS mode is requested. If FIPS mode is not requested, this method does nothing.
     * Otherwise, ensure that no password can be leaked.
     * @param useHTTPS is TLS used or not
     * @param password the password used
     * @throws IllegalArgumentException if there is a risk that the password will leak
     */
    public static void ensureNoPasswordLeak(boolean useHTTPS, String password) {
        ensureNoPasswordLeak(useHTTPS, !StringUtils.isEmpty(password));
    }

    /**
     * Password leak prevention when FIPS mode is requested. If FIPS mode is not requested, this method does nothing.
     * Otherwise, ensure that no password can be leaked.
     * @param useHTTPS is TLS used or not
     * @param usePassword is a password used
     * @throws IllegalArgumentException if there is a risk that the password will leak
     */
    public static void ensureNoPasswordLeak(boolean useHTTPS, boolean usePassword) {
        if (FIPS140.useCompliantAlgorithms()) {
            if (!useHTTPS && usePassword) {
                throw new IllegalArgumentException(Messages.AmazonEC2Cloud_tlsIsRequiredInFIPSMode());
            }
        }
    }

    /**
     * Password leak prevention when FIPS mode is requested. If FIPS mode is not requested, this method does nothing.
     * Otherwise, ensure that no password can be leaked.
     * @param allowSelfSignedCertificate is self-signed certificate allowed
     * @throws IllegalArgumentException if FIPS mode is requested and a self-signed certificate is allowed
     */
    public static void ensureNoSelfSignedCertificate(boolean allowSelfSignedCertificate) {
        if (FIPS140.useCompliantAlgorithms()) {
            if (allowSelfSignedCertificate) {
                throw new IllegalArgumentException(Messages.AmazonEC2Cloud_selfSignedCertificateNotAllowedInFIPSMode());
            }
        }
    }

    /**
     * Checks if the private key is allowed when FIPS mode is requested.
     * Allowed private key with the following algorithms and sizes:
     * <ul>
     *     <li>DSA with key size >= 2048</li>
     *     <li>RSA with key size >= 2048</li>
     *     <li>Elliptic curve (ED25519) with field size >= 224</li>
     * </ul>
     * If the private key is valid and allowed or not in FIPS mode method will just exit.
     * If not it will throw an {@link IllegalArgumentException}.
     * @param privateKeyString String containing the private key PEM.
     */
    public static void ensurePrivateKeyInFipsMode(String privateKeyString) {
        if (!FIPS140.useCompliantAlgorithms()) {
            return;
        }
        if (StringUtils.isBlank(privateKeyString)) {
            throw new IllegalArgumentException(Messages.AmazonEC2Cloud_keyIsMandatoryInFIPSMode());
        }
        try {
            Key privateKey = PEMEncodable.decode(privateKeyString).toPrivateKey();
            ensureKeyInFipsMode(privateKey);
        } catch (RuntimeException | UnrecoverableKeyException | IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    public static void ensurePublicKeyInFipsMode(@NonNull String algorithm, @NonNull byte[] key) {
        if (!FIPS140.useCompliantAlgorithms()) {
            return;
        }

        KeyAlgorithm<PublicKey, PrivateKey> publicKeyPrivateKeyKeyAlgorithm =
                KeyAlgorithmManager.getSupportedAlgorithms().stream()
                        .filter((keyAlgorithm) -> keyAlgorithm.getKeyFormat().equals(algorithm))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                Messages.AmazonEC2Cloud_keyIsNotApprovedInFIPSMode(algorithm)));
        try {
            Key publicKey = publicKeyPrivateKeyKeyAlgorithm.decodePublicKey(key);
            ensureKeyInFipsMode(publicKey);
        } catch (RuntimeException | IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
