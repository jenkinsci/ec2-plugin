package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.ExtensionList;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "true")
class WindowsDataWithFIPSTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link Descriptor.FormException} is expected
     */
    @Test
    @WithoutJenkins
    void testSelfSignedCertificateNotAllowed() {
        assertThrows(Descriptor.FormException.class, () -> new WindowsData("", true, "", true, true));
    }

    /**
     * Using a password without using TLS, an {@link Descriptor.FormException} is expected
     */
    @Test
    @WithoutJenkins
    void testCreateWindowsDataWithPasswordWithoutTLS() {
        assertThrows(
                Descriptor.FormException.class, () -> new WindowsData("01234567890123456789", false, "", true, false));
    }

    /**
     * Using a password less than 14 chars, an {@link Descriptor.FormException} is expected
     */
    @Test
    @WithoutJenkins
    void testCreateWindowsDataWithShortPassword() {
        assertThrows(Descriptor.FormException.class, () -> new WindowsData("0123", true, "", true, false));
    }

    /**
     * Using a password with TLS, an {@link Descriptor.FormException} is not expected
     */
    @Test
    @WithoutJenkins
    void testCreateWindowsDataWithPasswordWithTLS() throws Descriptor.FormException {
        new WindowsData("01234567890123456789", true, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("01234567890123456789", true, "", false, false);
    }

    /**
     * If no password is used TLS can have any value, an {@link Descriptor.FormException} is not expected
     */
    @Test
    @WithoutJenkins
    void testCreateWindowsDataWithoutPassword() throws Descriptor.FormException {
        new WindowsData("", false, "", false, false);
        new WindowsData("", true, "", false, false);
    }

    @Test
    void testDoCheckUseHTTPSWithPassword() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(true, "yes");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    void testDoCheckUseHTTPSWithoutPassword() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(true, "");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    void testDoCheckUseHTTPWithPassword() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(false, "yes");
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    void testDoCheckUseHTTPWithoutPassword() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(false, "");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    void testDoCheckAllowSelfSignedCertificateChecked() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckAllowSelfSignedCertificate(true);
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    void testDoCheckAllowSelfSignedCertificateNotChecked() {
        FormValidation formValidation = ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class)
                .doCheckAllowSelfSignedCertificate(false);
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    void testDoCheckPasswordLengthLessThan14() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckPassword("123");
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    void testDoCheckPasswordLengthGreaterThan14() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckPassword("12345678901234567890");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }
}
