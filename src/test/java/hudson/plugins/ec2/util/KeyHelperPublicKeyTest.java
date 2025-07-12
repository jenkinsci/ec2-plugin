package hudson.plugins.ec2.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.security.PrivateKey;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class KeyHelperPublicKeyTest {

    public static final PrivateKey MOCK_PRIVATE_KEY = new PrivateKey() {
        @Override
        public String getAlgorithm() {
            return "MOCK";
        }

        @Override
        public String getFormat() {
            return "MOCK";
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
                KeyHelperTestHelper.generateECKey("secp256r1").getPrivate(),
                false
            },
            {
                "EC curve NIST P-384",
                KeyHelperTestHelper.generateECKey("secp384r1").getPrivate(),
                false
            },
            {
                "EC curve NIST P-521",
                KeyHelperTestHelper.generateECKey("secp521r1").getPrivate(),
                false
            },
            {"RSA 1024", KeyHelperTestHelper.generateRSAKey(1024).getPrivate(), false},
            {"RSA 2048", KeyHelperTestHelper.generateRSAKey(2048).getPrivate(), false},
            {"RSA 4096", KeyHelperTestHelper.generateRSAKey(4096).getPrivate(), false},
            {"EdDSA", KeyHelperTestHelper.generateEdDSAKey().getPrivate(), false},
            {"unknown", MOCK_PRIVATE_KEY, true}
        };
    }

    @ParameterizedTest
    @MethodSource("data")
    void testGeneratePublicKeyFromPrivateKey(String description, PrivateKey privateKey, boolean isNullExpected) {
        if (isNullExpected) {
            assertNull(KeyHelper.generatePublicKeyFromPrivateKey(null, privateKey), description);
        } else {
            PrivateKeyInfo info = PrivateKeyInfo.getInstance(privateKey.getEncoded());
            assertNotNull(KeyHelper.generatePublicKeyFromPrivateKey(info, privateKey), description);
        }
    }
}
