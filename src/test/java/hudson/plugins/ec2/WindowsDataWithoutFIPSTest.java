package hudson.plugins.ec2;

import hudson.plugins.ec2.win.WinConnection;
import hudson.util.FormValidation;
import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;

import java.net.MalformedURLException;

import static org.junit.Assert.assertEquals;

public class WindowsDataWithoutFIPSTest {
    @ClassRule
    public static FlagRule<String>
            fipsSystemPropertyRule = FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "false");

    /**
     * When FIPS is not enabled, it should always be allowed to create the {@link WindowsData}, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testWinConnectionCreation() {
        new WindowsData("", true, "", true, true);
        new WindowsData("yes", true, "", true, true);
        new WindowsData("", false, "", true, true);
        new WindowsData("yes", false, "", true, true);

        new WindowsData("", true, "", false, true);
        new WindowsData("yes", true, "", false, true);
        new WindowsData("", false, "", false, true);
        new WindowsData("yes", false, "", false, true);


        new WindowsData("", true, "", true, false);
        new WindowsData("yes", true, "", true, false);
        new WindowsData("", false, "", true, false);
        new WindowsData("yes", false, "", true, false);

        new WindowsData("", true, "", false, false);
        new WindowsData("yes", true, "", false, false);
        new WindowsData("", false, "", false, false);
        new WindowsData("yes", false, "", false, false);
    }


    @Test
    public void testDoCheckUseHTTPSWithPassword() {
        FormValidation formValidation = new WindowsData.DescriptorImpl().doCheckUseHTTPS(true, "yes");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckUseHTTPSWithoutPassword() {
        FormValidation formValidation = new WindowsData.DescriptorImpl().doCheckUseHTTPS(true, "");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckUseHTTPWithPassword() {
        FormValidation formValidation = new WindowsData.DescriptorImpl().doCheckUseHTTPS(false, "yes");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckUseHTTPWithoutPassword() {
        FormValidation formValidation = new WindowsData.DescriptorImpl().doCheckUseHTTPS(false, "");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckAllowSelfSignedCertificateChecked() {
        FormValidation formValidation = new WindowsData.DescriptorImpl().doCheckAllowSelfSignedCertificate(true);
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckAllowSelfSignedCertificateNotChecked() {
        FormValidation formValidation = new WindowsData.DescriptorImpl().doCheckAllowSelfSignedCertificate(false);
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }
}