package hudson.plugins.ec2.util;

import hudson.plugins.ec2.Messages;
import jenkins.security.FIPS140;

import java.net.URL;
import java.security.Key;
import java.security.interfaces.DSAKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;

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
                    throw new IllegalArgumentException(Messages.AmazonEC2Cloud_invalidKeySize());
                }
            } else if (key instanceof DSAKey) {
                if (((DSAKey) key).getParams().getP().bitLength() < 2048) {
                    throw new IllegalArgumentException(Messages.AmazonEC2Cloud_invalidKeySize());
                }
            } else if (key instanceof ECKey) {
                if (((ECKey) key).getParams().getCurve().getField().getFieldSize() < 224) {
                    throw new IllegalArgumentException(Messages.AmazonEC2Cloud_invalidKeySizeEC());
                }
            } else {
                throw new IllegalArgumentException(Messages.AmazonEC2Cloud_keyIsNotApprovedInFIPSMode(key.getAlgorithm()));
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static boolean isNotEmpty(String password) {
        return password != null && !password.isEmpty();
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
        ensureNoPasswordLeak(useHTTPS, isNotEmpty(password));
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
}
