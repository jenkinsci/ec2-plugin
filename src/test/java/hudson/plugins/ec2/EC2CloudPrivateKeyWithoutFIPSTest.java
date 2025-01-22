package hudson.plugins.ec2;

import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_DSA_1024;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_ECDSA_256;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_ECDSA_384;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_ECDSA_521;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_RSA_1024;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_RSA_2048;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_RSA_3072;
import static hudson.plugins.ec2.EC2CloudPrivateKeyWithFIPSTest.PRIVATE_KEY_RSA_4096;
import static org.junit.Assert.fail;

import hudson.plugins.ec2.util.FIPS140Utils;
import java.util.Arrays;
import java.util.Collection;
import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.FlagRule;

@RunWith(Parameterized.class)
public class EC2CloudPrivateKeyWithoutFIPSTest {
    @ClassRule
    public static FlagRule<String> fipsSystemPropertyRule =
            FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "false");

    private final String description;

    private final String privateKey;

    public EC2CloudPrivateKeyWithoutFIPSTest(String description, String privateKey) {
        this.description = description;
        this.privateKey = privateKey;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
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

    @Test
    public void testPrivateKeyValidation() {
        try {
            FIPS140Utils.ensurePrivateKeyInFipsMode(privateKey);
        } catch (IllegalArgumentException e) {
            fail(description + " should be valid");
        }
    }
}
