package hudson.plugins.ec2;

import static hudson.plugins.ec2.HostKeyWithFIPSTest.PUBLIC_KEY_ECDSA_SHA2_NISTP256_256;
import static hudson.plugins.ec2.HostKeyWithFIPSTest.PUBLIC_KEY_ECDSA_SHA2_NISTP384_384;
import static hudson.plugins.ec2.HostKeyWithFIPSTest.PUBLIC_KEY_ECDSA_SHA2_NISTP521_521;
import static hudson.plugins.ec2.HostKeyWithFIPSTest.PUBLIC_KEY_SSH_DSS_1024;
import static hudson.plugins.ec2.HostKeyWithFIPSTest.PUBLIC_KEY_SSH_RSA_1024;
import static hudson.plugins.ec2.HostKeyWithFIPSTest.PUBLIC_KEY_SSH_RSA_2048;
import static hudson.plugins.ec2.HostKeyWithFIPSTest.PUBLIC_KEY_SSH_RSA_3072;
import static hudson.plugins.ec2.HostKeyWithFIPSTest.PUBLIC_KEY_SSH_RSA_4096;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.plugins.ec2.ssh.verifiers.HostKey;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "false")
class HostKeyWithoutFIPSTest {

    @BeforeAll
    static void setUp() {
        // Add provider manually to avoid requiring jenkinsrule
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
            {"SSH-DSS with key size 1024", "ssh-dss", PUBLIC_KEY_SSH_DSS_1024},
            {"SSH-RSA with key size 1024", "ssh-rsa", PUBLIC_KEY_SSH_RSA_1024},
            {"SSH-RSA with key size 2048", "ssh-rsa", PUBLIC_KEY_SSH_RSA_2048},
            {"SSH-RSA with key size 3072", "ssh-rsa", PUBLIC_KEY_SSH_RSA_3072},
            {"SSH-RSA with key size 4096", "ssh-rsa", PUBLIC_KEY_SSH_RSA_4096},
            {"ECDSA-SHA2-NISTP256 with key size 256", "ecdsa-sha2-nistp256", PUBLIC_KEY_ECDSA_SHA2_NISTP256_256},
            {"ECDSA-SHA2-NISTP384 with key size 384", "ecdsa-sha2-nistp384", PUBLIC_KEY_ECDSA_SHA2_NISTP384_384},
            {"ECDSA-SHA2-NISTP521 with key size 521", "ecdsa-sha2-nistp521", PUBLIC_KEY_ECDSA_SHA2_NISTP521_521}
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    void testPublicKeyValidation(String description, String algorithm, String publicKey) {
        try {
            new HostKey(algorithm, Base64.getDecoder().decode(publicKey));
        } catch (IllegalArgumentException e) {
            fail(description + " should be valid");
        }
    }
}
