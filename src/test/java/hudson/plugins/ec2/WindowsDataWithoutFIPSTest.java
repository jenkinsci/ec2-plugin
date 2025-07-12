package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "false")
class WindowsDataWithoutFIPSTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    /**
     * When FIPS is not enabled, it should always be allowed to create the {@link WindowsData}, an {@link IllegalArgumentException} is not expected
     */
    @Test
    @WithoutJenkins
    void testWinConnectionCreation() throws Descriptor.FormException {
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
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
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
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
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
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    void testDoCheckPasswordLengthGreaterThan14() {
        FormValidation formValidation =
                ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class).doCheckPassword("12345678901234567890");
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }
}
