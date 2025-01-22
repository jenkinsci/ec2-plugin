package hudson.plugins.ec2;

import static org.junit.Assert.assertEquals;

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

public class WindowsDataWithoutFIPSTest {
    @ClassRule
    public static FlagRule<String> fipsSystemPropertyRule =
            FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "false");

    @Rule
    public JenkinsRule j = new JenkinsRule();

    /**
     * When FIPS is not enabled, it should always be allowed to create the {@link WindowsData}, an {@link IllegalArgumentException} is not expected
     */
    @Test
    @WithoutJenkins
    public void testWinConnectionCreation() throws Descriptor.FormException {
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
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
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
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }

    @Test
    public void testDoCheckAllowSelfSignedCertificateNotChecked() {
        FormValidation formValidation = ExtensionList.lookupSingleton(WindowsData.DescriptorImpl.class)
                .doCheckAllowSelfSignedCertificate(false);
        assertEquals(FormValidation.Kind.OK, formValidation.kind);
    }
}
