package hudson.plugins.ec2.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.security.PrivateKey;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class KeyHelperPublicKeyTest extends KeyHelperTestAbstract {

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

    private final String description;

    private final PrivateKey privateKey;

    private final boolean isNullExpected;

    public KeyHelperPublicKeyTest(String description, PrivateKey privateKey, boolean isNullExpected) {
        this.description = description;
        this.privateKey = privateKey;
        this.isNullExpected = isNullExpected;
    }

    @Parameterized.Parameters
    public static Object[] data() throws Exception {
        return new Object[][] {
            {"EC curve NIST P-256", generateECKey("secp256r1").getPrivate(), false},
            {"EC curve NIST P-384", generateECKey("secp384r1").getPrivate(), false},
            {"EC curve NIST P-521", generateECKey("secp521r1").getPrivate(), false},
            {"RSA 1024", generateRSAKey(1024).getPrivate(), false},
            {"RSA 2048", generateRSAKey(2048).getPrivate(), false},
            {"RSA 4096", generateRSAKey(4096).getPrivate(), false},
            {"EdDSA", generateEdDSAKey().getPrivate(), false},
            {"unknown", MOCK_PRIVATE_KEY, true}
        };
    }

    @Test
    public void testGeneratePublicKeyFromPrivateKey() {
        if (isNullExpected) {
            assertNull(description, KeyHelper.generatePublicKeyFromPrivateKey(null, privateKey));
        } else {
            PrivateKeyInfo info = PrivateKeyInfo.getInstance(privateKey.getEncoded());
            assertNotNull(description, KeyHelper.generatePublicKeyFromPrivateKey(info, privateKey));
        }
    }
}
