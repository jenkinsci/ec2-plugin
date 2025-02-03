package hudson.plugins.ec2;

import static org.junit.Assert.*;

import hudson.ExtensionList;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;

public class WindowsDataWithFIPSTest {
    @ClassRule
    public static FlagRule<String> fipsSystemPropertyRule =
            FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "true");

    @Rule
    public JenkinsRule j = new JenkinsRule();

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link Descriptor.FormException} is expected
     */
    @Test
    @WithoutJenkins
    public void testSelfSignedCertificateNotAllowed() {
        assertThrows(Descriptor.FormException.class, () -> new WindowsData("", true, "", true, true));
    }

    /**
     * Using a password without using TLS, an {@link Descriptor.FormException} is expected
     */
    @Test
    @WithoutJenkins
    public void testCreateWindowsDataWithPasswordWithoutTLS() {
        assertThrows(
                Descriptor.FormException.class, () -> new WindowsData("01234567890123456789", false, "", true, false));
    }

    /**
     * Using a password less than 14 chars, an {@link Descriptor.FormException} is expected
     */
    @Test
    @WithoutJenkins
    public void testCreateWindowsDataWithShortPassword() {
        assertThrows(Descriptor.FormException.class, () -> new WindowsData("0123", true, "", true, false));
    }

    /**
     * Using a password with TLS, an {@link Descriptor.FormException} is not expected
     */
    @Test
    @WithoutJenkins
    public void testCreateWindowsDataWithPasswordWithTLS() throws Descriptor.FormException {
        new WindowsData("01234567890123456789", true, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("01234567890123456789", true, "", false, false);
    }

    /**
     * If no password is used TLS can have any value, an {@link Descriptor.FormException} is not expected
     */
    @Test
    @WithoutJenkins
    public void testCreateWindowsDataWithoutPassword() throws Descriptor.FormException {
        new WindowsData("", false, "", false, false);
        new WindowsData("", true, "", false, false);
    }

    @Test
    public void testDoCheckUseHTTPSWithPassword() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(true, "yes");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckUseHTTPSWithoutPassword() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(true, "");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckUseHTTPWithPassword() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(false, "yes");
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    public void testDoCheckUseHTTPWithoutPassword() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(false, "");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckAllowSelfSignedCertificateChecked() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckAllowSelfSignedCertificate(true);
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    public void testDoCheckAllowSelfSignedCertificateNotChecked() {
        FormValidation formValidation = ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class)
                .doCheckAllowSelfSignedCertificate(false);
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckPasswordLengthLessThan14() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckPassword("123");
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    public void testDoCheckPasswordLengthGreaterThan14() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckPassword("12345678901234567890");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }
}
