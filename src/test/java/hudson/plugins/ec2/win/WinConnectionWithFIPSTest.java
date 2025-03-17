package hudson.plugins.ec2.win;

import static org.junit.Assert.assertThrows;

import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;

public class WinConnectionWithFIPSTest {

    @ClassRule
    public static FlagRule<String> fipsSystemPropertyRule =
            FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "true");

    /**
     * WinConnection class cannot be instantiated in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test
    public void testInstantiationWinConnection() {
        assertThrows(IllegalArgumentException.class, () -> new WinConnection("", "", "", true));
    }
}
