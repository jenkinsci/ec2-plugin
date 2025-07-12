package hudson.plugins.ec2;

import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_DSA_1024;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_ECDSA_256;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_ECDSA_384;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_ECDSA_521;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_RSA_1024;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_RSA_2048;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_RSA_3072;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_RSA_4096;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.plugins.ec2.util.FIPS140Utils;
import java.util.Arrays;
import java.util.Collection;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "false")
class EC2CloudPrivateKeyWithoutFIPSTest {

    static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
            {"DSA with key size 1024", PRIVATE_KEY_DSA_1024},
            {"RSA with key size 1024", PRIVATE_KEY_RSA_1024},
            {"RSA with key size 2048", PRIVATE_KEY_RSA_2048},
            {"RSA with key size 3072", PRIVATE_KEY_RSA_3072},
            {"RSA with key size 4096", PRIVATE_KEY_RSA_4096},
            {"ECDSA with key size 256", PRIVATE_KEY_ECDSA_256},
            {"ECDSA with key size 384", PRIVATE_KEY_ECDSA_384},
            {"ECDSA with key size 521", PRIVATE_KEY_ECDSA_521},
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    void testPrivateKeyValidation(String description, String privateKey) {
        try {
            FIPS140Utils.ensurePrivateKeyInFipsMode(privateKey);
        } catch (IllegalArgumentException e) {
            fail(description + " should be valid");
        }
    }
}
