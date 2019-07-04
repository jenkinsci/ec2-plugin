package hudson.plugins.ec2;

import java.util.List;

import jenkins.model.Jenkins;
import hudson.util.Secret;
import hudson.model.labels.LabelAtom;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.model.CNode;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static io.jenkins.plugins.casc.misc.Util.toStringFromYamlFile;

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
        assertEquals(0, ec2Cloud.getTemplates().size());
    }

    @Test
    @ConfiguredWithCode("UnixData.yml")
    public void testUnixData() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-production");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals("ami-12345", slaveTemplate.getAmi());
        assertEquals("/home/ec2-user", slaveTemplate.remoteFS);

        assertEquals("linux ubuntu", slaveTemplate.getLabelString());
        assertEquals(2, slaveTemplate.getLabelSet().size());

        assertTrue(ec2Cloud.canProvision(new LabelAtom("ubuntu")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("linux")));

        final AMITypeData amiType = slaveTemplate.getAmiType();
        assertTrue(amiType.isUnix());
        assertTrue(amiType instanceof UnixData);
        final UnixData unixData = (UnixData) amiType;
        assertEquals("sudo", unixData.getRootCommandPrefix());
        assertEquals("sudo -u jenkins", unixData.getSlaveCommandPrefix());
        assertEquals("-fakeFlag", unixData.getSlaveCommandSuffix());
        assertEquals("22", unixData.getSshPort());
    }

    @Test
    @ConfiguredWithCode("Unix.yml")
    public void testUnix() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-staging");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals("ami-5678", slaveTemplate.getAmi());
        assertEquals("/mnt/jenkins", slaveTemplate.remoteFS);

        assertEquals("linux clear", slaveTemplate.getLabelString());
        assertEquals(2, slaveTemplate.getLabelSet().size());

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
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals("ami-abc123", slaveTemplate.getAmi());
        assertEquals("C:/jenkins", slaveTemplate.remoteFS);

        assertEquals("windows vs2019", slaveTemplate.getLabelString());
        assertEquals(2, slaveTemplate.getLabelSet().size());

        assertTrue(ec2Cloud.canProvision(new LabelAtom("windows")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("vs2019")));

        final AMITypeData amiType = slaveTemplate.getAmiType();
        assertTrue(!amiType.isUnix());
        assertTrue(amiType.isWindows());
        assertTrue(amiType instanceof WindowsData);
        final WindowsData windowsData = (WindowsData) amiType;
        assertEquals(Secret.fromString("password"), windowsData.getPassword());
        assertTrue(windowsData.isUseHTTPS());
        assertEquals("180", windowsData.getBootDelay());
    }

    @Test
    @ConfiguredWithCode("BackwardsCompatibleConnectionStrategy.yml")
    public void testBackwardsCompatibleConnectionStrategy() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-us-east-1");
        assertNotNull(ec2Cloud);

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(ConnectionStrategy.PRIVATE_DNS,slaveTemplate.connectionStrategy);
    }

    @Test
    @ConfiguredWithCode("BackwardCompatibleT2UnlimitedDefault.yml")
    public void testBackwardCompatibleT2UnlimitedDefault() throws Exception {
        // Not setting t2Unlimited in the JCasC config file should yield BurstableUnlimitedMode.DEFAULT
        // (since the default of t2Unlimited==false used to _not_ send any Unlimited Mode preference to AWS):
        SlaveTemplate.BurstableUnlimitedMode expectedBurstableUnlimitedMode = SlaveTemplate.BurstableUnlimitedMode.DEFAULT;

        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-us-east-1");
        assertNotNull(ec2Cloud);

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());

        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(expectedBurstableUnlimitedMode, slaveTemplate.getBurstableUnlimitedMode());
    }

    @Test
    @ConfiguredWithCode("BackwardCompatibleT2UnlimitedEnabled.yml")
    public void testBackwardCompatibleT2UnlimitedEnabled() throws Exception {
        // t2Unlimited=true in the JCasC config file should yield BurstableUnlimitedMode.ENABLED:
        SlaveTemplate.BurstableUnlimitedMode expectedBurstableUnlimitedMode = SlaveTemplate.BurstableUnlimitedMode.ENABLED;

        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-us-east-1");
        assertNotNull(ec2Cloud);

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());

        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(expectedBurstableUnlimitedMode, slaveTemplate.getBurstableUnlimitedMode());
    }

    @Test
    @ConfiguredWithCode("BackwardCompatibleT2UnlimitedDisabled.yml")
    public void testBackwardCompatibleT2UnlimitedDisabled() throws Exception {
        // t2Unlimited=false in the JCasC config file should yield BurstableUnlimitedMode.DEFAULT
        // (since t2Unlimited==false used to _not_ send any Unlimited Mode preference to AWS):
        SlaveTemplate.BurstableUnlimitedMode expectedBurstableUnlimitedMode = SlaveTemplate.BurstableUnlimitedMode.DEFAULT;

        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-us-east-1");
        assertNotNull(ec2Cloud);

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());

        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(expectedBurstableUnlimitedMode, slaveTemplate.getBurstableUnlimitedMode());
    }

    @Test
    @ConfiguredWithCode("BurstableUnlimitedMode.yml")
    public void testBurstableUnlimitedMode() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("ec2-us-east-1");
        assertNotNull(ec2Cloud);

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(3, templates.size());

        assertEquals(SlaveTemplate.BurstableUnlimitedMode.DEFAULT, templates.get(0).getBurstableUnlimitedMode());
        assertEquals(SlaveTemplate.BurstableUnlimitedMode.ENABLED, templates.get(1).getBurstableUnlimitedMode());
        assertEquals(SlaveTemplate.BurstableUnlimitedMode.DISABLED, templates.get(2).getBurstableUnlimitedMode());
    }

    @Test
    @ConfiguredWithCode("UnixData.yml")
    public void testConfigAsCodeExport() throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        CNode clouds = getJenkinsRoot(context).get("clouds");
        String exported = toYamlString(clouds);
        String expected = toStringFromYamlFile(this, "UnixDataExport.yml");
        assertEquals(expected, exported);
    }
}
