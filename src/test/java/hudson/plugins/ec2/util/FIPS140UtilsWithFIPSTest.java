package hudson.plugins.ec2.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import hudson.plugins.ec2.Messages;
import io.vavr.CheckedRunnable;
import java.net.URL;
import java.security.Key;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAPublicKey;
import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;
import org.mockito.Mockito;

public class FIPS140UtilsWithFIPSTest {
    @ClassRule
    public static FlagRule<String> fipsSystemPropertyRule =
            FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "true");

    private void assertInvalidKey(Key key, String expectedMessage) {
        try {
            FIPS140Utils.ensureKeyInFipsMode(key);
            fail("Should be invalid with message: " + expectedMessage);
        } catch (IllegalArgumentException e) {
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    private void assertValidKey(Key key) {
        try {
            FIPS140Utils.ensureKeyInFipsMode(key);
        } catch (IllegalArgumentException e) {
            fail("Should be valid key but failed with message: " + e.getMessage());
        }
    }

    private void assertTLSIsNonCompliant(CheckedRunnable block) {
        try {
            block.run();
            fail("Should be invalid with message: " + Messages.EC2Cloud_tlsIsRequiredInFIPSMode());
        } catch (IllegalArgumentException e) {
            assertEquals(Messages.EC2Cloud_tlsIsRequiredInFIPSMode(), e.getMessage());
        } catch (Throwable e) {
            fail("Unexpected error: " + e.getMessage());
        }
    }

    private void assertTLSIsCompliant(CheckedRunnable block) {
        try {
            block.run();
        } catch (IllegalArgumentException e) {
            fail("TLS should not be required: " + e.getMessage());
        } catch (Throwable e) {
            fail("Unexpected error: " + e.getMessage());
        }
    }

    @Test
    public void testDSAInvalidKeyMessage() {
        DSAPublicKey key = Mockito.mock(DSAPublicKey.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(key.getParams().getP().bitLength()).thenReturn(2047);

        assertInvalidKey(key, Messages.EC2Cloud_invalidKeySizeInFIPSMode());
    }

    @Test
    public void testDSAValidKey() {
        DSAPublicKey key = Mockito.mock(DSAPublicKey.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(key.getParams().getP().bitLength()).thenReturn(2048);

        assertValidKey(key);
    }

    @Test
    public void testRSAInvalidKeyMessage() {
        RSAPublicKey key = Mockito.mock(RSAPublicKey.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(key.getModulus().bitLength()).thenReturn(2047);

        assertInvalidKey(key, Messages.EC2Cloud_invalidKeySizeInFIPSMode());
    }

    @Test
    public void testRSAValidKey() {
        RSAPublicKey key = Mockito.mock(RSAPublicKey.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(key.getModulus().bitLength()).thenReturn(2048);

        assertValidKey(key);
    }

    @Test
    public void testECDSAInvalidKeyMessage() {
        ECKey key = Mockito.mock(
                ECKey.class,
                Mockito.withSettings().extraInterfaces(Key.class).defaultAnswer(Mockito.RETURNS_DEEP_STUBS));
        Mockito.when(key.getParams().getCurve().getField().getFieldSize()).thenReturn(223);

        assertInvalidKey((Key) key, Messages.EC2Cloud_invalidKeySizeECInFIPSMode());
    }

    @Test
    public void testECDSAValidKey() {
        ECKey key = Mockito.mock(
                ECKey.class,
                Mockito.withSettings().extraInterfaces(Key.class).defaultAnswer(Mockito.RETURNS_DEEP_STUBS));
        Mockito.when(key.getParams().getCurve().getField().getFieldSize()).thenReturn(224);

        assertValidKey((Key) key);
    }

    @Test
    public void testUnknownInstance() {
        String message = "My mock algorithm";
        Key key = Mockito.mock(Key.class);
        Mockito.when(key.getAlgorithm()).thenReturn(message);

        assertInvalidKey(key, Messages.EC2Cloud_keyIsNotApprovedInFIPSMode(message));
    }

    @Test
    public void testRuntimeException() {
        String message = "The test message";
        Key key = Mockito.mock(Key.class);
        Mockito.when(key.getAlgorithm()).thenThrow(new RuntimeException(message));

        assertInvalidKey(key, message);
    }

    @Test
    public void testTLSCheckWithHTTPAndPassword() {
        assertTLSIsNonCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("http://localhost"), "non-empty"));
    }

    @Test
    public void testTLSCheckWithHTTPSAndPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("https://localhost"), "non-empty"));
    }

    @Test
    public void testTLSCheckWithHTTPAndNullPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("http://localhost"), null));
    }

    @Test
    public void testTLSCheckWithHTTPSAndNullPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("https://localhost"), null));
    }

    @Test
    public void testTLSCheckWithHTTPAndNoPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("http://localhost"), ""));
    }

    @Test
    public void testTLSCheckWithHTTPSAndNoPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("https://localhost"), ""));
    }

    @Test
    public void testNotAllowSelfSignedCertificate() {
        try {
            FIPS140Utils.ensureNoSelfSignedCertificate(false);
        } catch (IllegalArgumentException e) {
            fail("Not allowing self-signed certificate should be valid, but got : " + e.getMessage());
        }
    }

    @Test
    public void testAllowSelfSignedCertificate() {
        String expectedMessage = Messages.EC2Cloud_selfSignedCertificateNotAllowedInFIPSMode();
        try {
            FIPS140Utils.ensureNoSelfSignedCertificate(true);
            fail("Should be invalid with message: " + expectedMessage);
        } catch (IllegalArgumentException e) {
            assertEquals(expectedMessage, e.getMessage());
        }
    }
}
