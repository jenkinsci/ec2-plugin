package hudson.plugins.ec2;

import hudson.ExtensionList;
import hudson.util.FormValidation;
import jenkins.security.FIPS140;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;

import static org.junit.Assert.*;

public class WindowsDataWithFIPSTest {
    @ClassRule
    public static FlagRule<String> fipsSystemPropertyRule = FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "true");

    @Rule
    public JenkinsRule j = new JenkinsRule();

    /**
     * Self-signed certificate should not be allowed in FIPS mode, an {@link IllegalArgumentException} is expected
     */
    @Test(expected = IllegalArgumentException.class)
    @WithoutJenkins
    public void testSelfSignedCertificateNotAllowed() {
        new WindowsData("", true, "", true, true);
    }

    /**
     * Using a password without using TLS, an {@link IllegalArgumentException} is expected
     */
    @Test(expected = IllegalArgumentException.class)
    @WithoutJenkins
    public void testCreateWindowsDataWithPasswordWithoutTLS() {
        new WindowsData("yes", false, "", true, false);
    }

    /**
     * Using a password with TLS, an {@link IllegalArgumentException} is not expected
     */
    @Test
    @WithoutJenkins
    public void testCreateWindowsDataWithPasswordWithTLS() {
        new WindowsData("yes", true, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("yes", true, "", false, false);
    }

    /**
     * If no password is used TLS can have any value, an {@link IllegalArgumentException} is not expected
     */
    @Test
    @WithoutJenkins
    public void testCreateWindowsDataWithoutPassword() {
        new WindowsData("", false, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("", false, "", false, false);

        new WindowsData("", true, "", true, false);
        // specifyPassword is set to true in the constructor
        new WindowsData("", true, "", false, false);
    }

    @Test
    @Ignore("Disabled until the plugins dependencies are FIPS compliant")
    public void testDoCheckUseHTTPSWithPassword() {
        FormValidation formValidation = ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(true, "yes");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    @Ignore("Disabled until the plugins dependencies are FIPS compliant")
    public void testDoCheckUseHTTPSWithoutPassword() {
        FormValidation formValidation = ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(true, "");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    @Ignore("Disabled until the plugins dependencies are FIPS compliant")
    public void testDoCheckUseHTTPWithPassword() {
        FormValidation formValidation = ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(false, "yes");
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    @Ignore("Disabled until the plugins dependencies are FIPS compliant")
    public void testDoCheckUseHTTPWithoutPassword() {
        FormValidation formValidation = ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckUseHTTPS(false, "");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    @Ignore("Disabled until the plugins dependencies are FIPS compliant")
    public void testDoCheckAllowSelfSignedCertificateChecked() {
        FormValidation formValidation = ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckAllowSelfSignedCertificate(true);
        assertEquals(FormValidation.Kind.ERROR, formValidation.kind);
    }

    @Test
    @Ignore("Disabled until the plugins dependencies are FIPS compliant")
    public void testDoCheckAllowSelfSignedCertificateNotChecked() {
        FormValidation formValidation = ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckAllowSelfSignedCertificate(false);
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }
}