package hudson.plugins.ec2.util;

import static org.junit.jupiter.api.Assertions.*;

import hudson.plugins.ec2.Messages;
import io.vavr.CheckedRunnable;
import java.net.URL;
import java.security.Key;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.mockito.Mockito;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "true")
class FIPS140UtilsWithFIPSTest {

    @Test
    void testDSAInvalidKeyMessage() {
        DSAPublicKey key = Mockito.mock(DSAPublicKey.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(key.getParams().getP().bitLength()).thenReturn(2047);

        assertInvalidKey(key, Messages.EC2Cloud_invalidKeySizeInFIPSMode());
    }

    @Test
    void testDSAValidKey() {
        DSAPublicKey key = Mockito.mock(DSAPublicKey.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(key.getParams().getP().bitLength()).thenReturn(2048);

        assertValidKey(key);
    }

    @Test
    void testRSAInvalidKeyMessage() {
        RSAPublicKey key = Mockito.mock(RSAPublicKey.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(key.getModulus().bitLength()).thenReturn(2047);

        assertInvalidKey(key, Messages.EC2Cloud_invalidKeySizeInFIPSMode());
    }

    @Test
    void testRSAValidKey() {
        RSAPublicKey key = Mockito.mock(RSAPublicKey.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(key.getModulus().bitLength()).thenReturn(2048);

        assertValidKey(key);
    }

    @Test
    void testECDSAInvalidKeyMessage() {
        ECKey key = Mockito.mock(
                ECKey.class,
                Mockito.withSettings().extraInterfaces(Key.class).defaultAnswer(Mockito.RETURNS_DEEP_STUBS));
        Mockito.when(key.getParams().getCurve().getField().getFieldSize()).thenReturn(223);

        assertInvalidKey((Key) key, Messages.EC2Cloud_invalidKeySizeECInFIPSMode());
    }

    @Test
    void testECDSAValidKey() {
        ECKey key = Mockito.mock(
                ECKey.class,
                Mockito.withSettings().extraInterfaces(Key.class).defaultAnswer(Mockito.RETURNS_DEEP_STUBS));
        Mockito.when(key.getParams().getCurve().getField().getFieldSize()).thenReturn(224);

        assertValidKey((Key) key);
    }

    @Test
    void testUnknownInstance() {
        String message = "My mock algorithm";
        Key key = Mockito.mock(Key.class);
        Mockito.when(key.getAlgorithm()).thenReturn(message);

        assertInvalidKey(key, Messages.EC2Cloud_keyIsNotApprovedInFIPSMode(message));
    }

    @Test
    void testTLSCheckWithHTTPAndPassword() {
        assertTLSIsNonCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("http://localhost"), "non-empty"));
    }

    @Test
    void testTLSCheckWithHTTPSAndPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("https://localhost"), "non-empty"));
    }

    @Test
    void testTLSCheckWithHTTPAndNullPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("http://localhost"), null));
    }

    @Test
    void testTLSCheckWithHTTPSAndNullPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("https://localhost"), null));
    }

    @Test
    void testTLSCheckWithHTTPAndNoPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("http://localhost"), ""));
    }

    @Test
    void testTLSCheckWithHTTPSAndNoPassword() {
        assertTLSIsCompliant(() -> FIPS140Utils.ensureNoPasswordLeak(new URL("https://localhost"), ""));
    }

    @Test
    void testNotAllowSelfSignedCertificate() {
        assertDoesNotThrow(
                () -> FIPS140Utils.ensureNoSelfSignedCertificate(false),
                "Not allowing self-signed certificate should be valid, but got : ");
    }

    @Test
    void testAllowSelfSignedCertificate() {
        String expectedMessage = Messages.EC2Cloud_selfSignedCertificateNotAllowedInFIPSMode();
        try {
            FIPS140Utils.ensureNoSelfSignedCertificate(true);
            fail("Should be invalid with message: " + expectedMessage);
        } catch (IllegalArgumentException e) {
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    private void assertInvalidKey(Key key, String expectedMessage) {
        try {
            FIPS140Utils.ensureKeyInFipsMode(key);
            fail("Should be invalid with message: " + expectedMessage);
        } catch (IllegalArgumentException e) {
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    private void assertValidKey(Key key) {
        assertDoesNotThrow(
                () -> FIPS140Utils.ensureKeyInFipsMode(key), "Should be valid key but failed with message: ");
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
}
