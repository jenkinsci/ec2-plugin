package hudson.plugins.ec2.win;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "true")
class WinConnectionWithFIPSTest {

    /**
     * WinConnection class cannot be instantiated in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test
    void testInstantiationWinConnection() {
        assertThrows(IllegalArgumentException.class, () -> new WinConnection("", "", "", true));
    }
}
