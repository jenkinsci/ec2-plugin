package hudson.plugins.ec2.util;

import static org.junit.Assert.assertEquals;

import java.security.PublicKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class KeyHelperSSHAlgorithmTest extends KeyHelperTestAbstract {

    public static final PublicKey MOCK_PUBLIC_KEY = new PublicKey() {
        @Override
        public String getAlgorithm() {
            return "Mock";
        }

        @Override
        public String getFormat() {
            return "Mock";
        }

        @Override
        public byte[] getEncoded() {
            return new byte[0];
        }
    };

    private final String description;

    private final PublicKey publicKey;

    private final String expected;

    public KeyHelperSSHAlgorithmTest(String description, PublicKey publicKey, String expected) {
        this.description = description;
        this.publicKey = publicKey;
        this.expected = expected;
    }

    @Parameterized.Parameters
    public static Object[] data() throws Exception {
        return new Object[][] {
            {"EC curve NIST P-256", generateECKey("secp256r1").getPublic(), "ecdsa-sha2-nistp256"},
            {"EC curve NIST P-384", generateECKey("secp384r1").getPublic(), "ecdsa-sha2-nistp384"},
            {"EC curve NIST P-521", generateECKey("secp521r1").getPublic(), "ecdsa-sha2-nistp521"},
            {"RSA 1024", generateRSAKey(1024).getPublic(), "ssh-rsa"},
            {"RSA 2048", generateRSAKey(2048).getPublic(), "ssh-rsa"},
            {"RSA 4096", generateRSAKey(4096).getPublic(), "ssh-rsa"},
            {"EdDSA", generateEdDSAKey().getPublic(), "ssh-ed25519"},
            {"unknown", MOCK_PUBLIC_KEY, null}
        };
    }

    @Test
    public void testSSHAlgorithm() throws Exception {
        assertEquals(description, expected, KeyHelper.getSshAlgorithm(publicKey));
    }
}
