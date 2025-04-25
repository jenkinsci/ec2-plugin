package hudson.plugins.ec2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.PublicKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class KeyHelperSSHAlgorithmTest {

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

    static Object[] data() throws Exception {
        return new Object[][] {
            {
                "EC curve NIST P-256",
                KeyHelperTestHelper.generateECKey("secp256r1").getPublic(),
                "ecdsa-sha2-nistp256"
            },
            {
                "EC curve NIST P-384",
                KeyHelperTestHelper.generateECKey("secp384r1").getPublic(),
                "ecdsa-sha2-nistp384"
            },
            {
                "EC curve NIST P-521",
                KeyHelperTestHelper.generateECKey("secp521r1").getPublic(),
                "ecdsa-sha2-nistp521"
            },
            {"RSA 1024", KeyHelperTestHelper.generateRSAKey(1024).getPublic(), "ssh-rsa"},
            {"RSA 2048", KeyHelperTestHelper.generateRSAKey(2048).getPublic(), "ssh-rsa"},
            {"RSA 4096", KeyHelperTestHelper.generateRSAKey(4096).getPublic(), "ssh-rsa"},
            {"EdDSA", KeyHelperTestHelper.generateEdDSAKey().getPublic(), "ssh-ed25519"},
            {"unknown", MOCK_PUBLIC_KEY, null}
        };
    }

    @ParameterizedTest
    @MethodSource("data")
    void testSSHAlgorithm(String description, PublicKey publicKey, String expected) {
        assertEquals(expected, KeyHelper.getSshAlgorithm(publicKey), description);
    }
}
