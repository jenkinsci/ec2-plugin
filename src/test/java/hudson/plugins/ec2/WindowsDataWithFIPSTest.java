package hudson.plugins.ec2;

import hudson.util.FormValidation;
import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;

import java.net.MalformedURLException;

import static org.junit.Assert.*;

public class WindowsDataWithFIPSTest {
    @ClassRule
    public static FlagRule<String>
            fipsSystemPropertyRule = FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "true");

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSelfSignedCertificateNotAllowed() {
        new WindowsData("", true, "", true, true);
    }

    /**
     * Using a password without using TLS, an {@link IllegalArgumentException} is expected
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateWindowsDataWithPasswordWithoutTLS() throws MalformedURLException {
        new WindowsData("yes", false, "", true, false);
    }

    /**
     * Using a password with TLS, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testCreateWindowsDataWithPasswordWithTLS() throws MalformedURLException {
        new WindowsData("yes", true, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("yes", true, "", false, false);
    }

    /**
     * If no password is used TLS can have any value, an {@link IllegalArgumentException} is not expected
     */
    @Test
    public void testCreateWindowsDataWithoutPassword() throws MalformedURLException {
        new WindowsData("", false, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("", false, "", false, false);

        new WindowsData("", true, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("", true, "", false, false);
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
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    public void testDoCheckUseHTTPWithoutPassword() {
        FormValidation formValidation = new WindowsData.DescriptorImpl().doCheckUseHTTPS(false, "");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckAllowSelfSignedCertificateChecked() {
        FormValidation formValidation = new WindowsData.DescriptorImpl().doCheckAllowSelfSignedCertificate(true);
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    public void testDoCheckAllowSelfSignedCertificateNotChecked() {
        FormValidation formValidation = new WindowsData.DescriptorImpl().doCheckAllowSelfSignedCertificate(false);
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }
}