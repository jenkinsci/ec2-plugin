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
    @Test(expected = Descriptor.FormException.class)
    @WithoutJenkins
    public void testSelfSignedCertificateNotAllowed() throws Descriptor.FormException {
        new WindowsData("", true, "", true, true);
    }

    /**
     * Using a password without using TLS, an {@link Descriptor.FormException} is expected
     */
    @Test(expected = Descriptor.FormException.class)
    @WithoutJenkins
    public void testCreateWindowsDataWithPasswordWithoutTLS() throws Descriptor.FormException {
        new WindowsData("yes", false, "", true, false);
    }

    /**
     * Using a password with TLS, an {@link Descriptor.FormException} is not expected
     */
    @Test
    @WithoutJenkins
    public void testCreateWindowsDataWithPasswordWithTLS() throws Descriptor.FormException {
        new WindowsData("yes", true, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("yes", true, "", false, false);
    }

    /**
     * If no password is used TLS can have any value, an {@link Descriptor.FormException} is not expected
     */
    @Test
    @WithoutJenkins
    public void testCreateWindowsDataWithoutPassword() throws Descriptor.FormException {
        new WindowsData("", false, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("", false, "", false, false);

        new WindowsData("", true, "", true, false);
        // specifyPassword is set to true in the constructor
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
