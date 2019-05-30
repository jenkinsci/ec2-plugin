package hudson.plugins.ec2;

import java.util.List;

import jenkins.model.Jenkins;
import hudson.util.Secret;
import hudson.model.labels.LabelAtom;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;

import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ConfigurationAsCodeTest {

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("EC2CloudEmpty.yml")
    public void testEmptyConfig() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-");
        assertNotNull(ec2Cloud);
        assertEquals(ec2Cloud.getTemplates().size(), 0);
    }

    @Test
    @ConfiguredWithCode("UnixData.yml")
    public void testUnixData() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-production");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(templates.size(), 1);
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(slaveTemplate.getAmi(), "ami-12345");
        assertEquals(slaveTemplate.remoteFS, "/home/ec2-user");

        assertEquals(slaveTemplate.getLabelString(), "linux ubuntu");
        assertEquals(slaveTemplate.getLabelSet().size(), 2);

        assertTrue(ec2Cloud.canProvision(new LabelAtom("ubuntu")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("linux")));

        final AMITypeData amiType = slaveTemplate.getAmiType();
        assertTrue(amiType.isUnix());
        assertTrue(amiType instanceof UnixData);
        final UnixData unixData = (UnixData) amiType;
        assertEquals(unixData.getRootCommandPrefix(), "sudo");
        assertEquals(unixData.getSlaveCommandPrefix(), "sudo -u jenkins");
        assertEquals(unixData.getSlaveCommandSuffix(), "-fakeFlag");
        assertEquals(unixData.getSshPort(), "22");
    }

    @Test
    @ConfiguredWithCode("Unix.yml")
    public void testUnix() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-staging");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(templates.size(), 1);
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(slaveTemplate.getAmi(), "ami-5678");
        assertEquals(slaveTemplate.remoteFS, "/mnt/jenkins");

        assertEquals(slaveTemplate.getLabelString(), "linux clear");
        assertEquals(slaveTemplate.getLabelSet().size(), 2);

        assertTrue(ec2Cloud.canProvision(new LabelAtom("clear")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("linux")));
    }

    @Test
    @ConfiguredWithCode("WindowsData.yml")
    public void testWindowsData() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-development");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(templates.size(), 1);
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(slaveTemplate.getAmi(), "ami-abc123");
        assertEquals(slaveTemplate.remoteFS, "C:/jenkins");

        assertEquals(slaveTemplate.getLabelString(), "windows vs2019");
        assertEquals(slaveTemplate.getLabelSet().size(), 2);

        assertTrue(ec2Cloud.canProvision(new LabelAtom("windows")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("vs2019")));

        final AMITypeData amiType = slaveTemplate.getAmiType();
        assertTrue(!amiType.isUnix());
        assertTrue(amiType.isWindows());
        assertTrue(amiType instanceof WindowsData);
        final WindowsData windowsData = (WindowsData) amiType;
        assertEquals(windowsData.getPassword(), Secret.fromString("password"));
        assertTrue(windowsData.isUseHTTPS());
        assertEquals(windowsData.getBootDelay(), "180");
    }
}