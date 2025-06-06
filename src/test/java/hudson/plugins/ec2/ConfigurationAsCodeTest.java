package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.labels.LabelAtom;
import hudson.plugins.ec2.util.MinimumNumberOfInstancesTimeRangeConfig;
import hudson.util.Secret;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.Util;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;

@WithJenkinsConfiguredWithCode
class ConfigurationAsCodeTest {

    @Test
    @ConfiguredWithCode("EC2CloudEmpty.yml")
    void testEmptyConfig(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("empty");
        assertNotNull(ec2Cloud);
        assertEquals(0, ec2Cloud.getTemplates().size());
    }

    @Test
    @ConfiguredWithCode("UnixData.yml")
    void testUnixData(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("production");
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

        final SpotConfiguration spotConfig = slaveTemplate.spotConfig;
        assertNotEquals(null, spotConfig);
        assertTrue(spotConfig.getFallbackToOndemand());
        assertEquals(3, spotConfig.getSpotBlockReservationDuration());
        assertEquals("0.15", spotConfig.getSpotMaxBidPrice());
        assertTrue(spotConfig.useBidPrice);

        final AMITypeData amiType = slaveTemplate.getAmiType();
        assertTrue(amiType.isUnix());
        assertInstanceOf(UnixData.class, amiType);
        final UnixData unixData = (UnixData) amiType;
        assertEquals("sudo", unixData.getRootCommandPrefix());
        assertEquals("sudo -u jenkins", unixData.getSlaveCommandPrefix());
        assertEquals("-fakeFlag", unixData.getSlaveCommandSuffix());
        assertEquals("22", unixData.getSshPort());
        assertEquals("180", unixData.getBootDelay());
    }

    @Test
    @ConfiguredWithCode("Unix.yml")
    void testUnix(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("staging");
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

        assertNull(slaveTemplate.spotConfig);
    }

    @Test
    @ConfiguredWithCode("WindowsData.yml")
    void testWindowsData(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("development");
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
        assertFalse(amiType.isUnix());
        assertTrue(amiType.isWindows());
        assertInstanceOf(WindowsData.class, amiType);
        final WindowsData windowsData = (WindowsData) amiType;
        assertEquals(Secret.fromString("password"), windowsData.getPassword());
        assertTrue(windowsData.isUseHTTPS());
        assertEquals("180", windowsData.getBootDelay());
    }

    @Test
    @ConfiguredWithCode("BackwardsCompatibleConnectionStrategy.yml")
    void testBackwardsCompatibleConnectionStrategy(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("us-east-1");
        assertNotNull(ec2Cloud);

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(ConnectionStrategy.PRIVATE_DNS, slaveTemplate.connectionStrategy);
    }

    @Test
    @ConfiguredWithCode("UnixData.yml")
    void testConfigAsCodeExport(JenkinsConfiguredWithCodeRule j) throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        CNode clouds = Util.getJenkinsRoot(context).get("clouds");
        String exported = Util.toYamlString(clouds);
        String expected = Util.toStringFromYamlFile(this, "UnixDataExport.yml");
        assertEquals(expected, exported);
    }

    @Test
    @ConfiguredWithCode("UnixData-withAltEndpointAndJavaPath.yml")
    void testConfigAsCodeWithAltEndpointAndJavaPathExport(JenkinsConfiguredWithCodeRule j) throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        CNode clouds = Util.getJenkinsRoot(context).get("clouds");
        String exported = Util.toYamlString(clouds);
        String expected = Util.toStringFromYamlFile(this, "UnixDataExport-withAltEndpointAndJavaPath.yml");
        assertEquals(expected, exported);
    }

    @Test
    @ConfiguredWithCode("Unix-withMinimumInstancesTimeRange.yml")
    void testConfigAsCodeWithMinimumInstancesTimeRange(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("timed");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals("ami-123456", slaveTemplate.getAmi());
        assertEquals("/home/ec2-user", slaveTemplate.remoteFS);

        assertEquals("linux ubuntu", slaveTemplate.getLabelString());
        assertEquals(2, slaveTemplate.getLabelSet().size());

        final MinimumNumberOfInstancesTimeRangeConfig timeRangeConfig =
                slaveTemplate.getMinimumNumberOfInstancesTimeRangeConfig();
        assertNotNull(timeRangeConfig);
        assertEquals(LocalTime.parse("01:00"), timeRangeConfig.getMinimumNoInstancesActiveTimeRangeFromAsTime());
        assertEquals(LocalTime.parse("13:00"), timeRangeConfig.getMinimumNoInstancesActiveTimeRangeToAsTime());
        assertFalse(timeRangeConfig.getDay("monday"));
        assertTrue(timeRangeConfig.getDay("tuesday"));
        assertFalse(timeRangeConfig.getDay("wednesday"));

        assertTrue(ec2Cloud.canProvision(new LabelAtom("ubuntu")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("linux")));
    }

    @Test
    @ConfiguredWithCode("Unix-withAvoidUsingOrphanedNodes.yml")
    void testConfigAsCodeWithAvoidUsingOrphanedNodes(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("avoidUsingOrphanedNodesTest");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertTrue(slaveTemplate.isAvoidUsingOrphanedNodes());
    }

    @Test
    @ConfiguredWithCode("Ami.yml")
    void testAmi(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("test");
        assertNotNull(ec2Cloud);

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(3, templates.size());

        SlaveTemplate slaveTemplate;
        List<EC2Filter> expectedFilters;

        slaveTemplate = templates.get(0);
        assertEquals("ami-0123456789abcdefg", slaveTemplate.ami);
        assertNull(slaveTemplate.getAmiOwners());
        assertNull(slaveTemplate.getAmiUsers());
        assertNull(slaveTemplate.getAmiFilters());

        slaveTemplate = templates.get(1);
        assertNull(slaveTemplate.ami);
        assertEquals("self", slaveTemplate.getAmiOwners());
        assertEquals("self", slaveTemplate.getAmiUsers());
        expectedFilters = Arrays.asList(new EC2Filter("name", "foo-*"), new EC2Filter("architecture", "x86_64"));
        assertEquals(expectedFilters, slaveTemplate.getAmiFilters());

        slaveTemplate = templates.get(2);
        assertNull(slaveTemplate.ami);
        assertNull(slaveTemplate.getAmiOwners());
        assertNull(slaveTemplate.getAmiUsers());
        expectedFilters = Collections.emptyList();
        assertEquals(expectedFilters, slaveTemplate.getAmiFilters());
    }

    @Test
    @ConfiguredWithCode("MacData.yml")
    void testMacData(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("production");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals("ami-12345", slaveTemplate.getAmi());
        assertEquals("/Users/ec2-user", slaveTemplate.remoteFS);

        assertEquals("mac metal", slaveTemplate.getLabelString());
        assertEquals(2, slaveTemplate.getLabelSet().size());

        assertTrue(ec2Cloud.canProvision(new LabelAtom("metal")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("mac")));

        final AMITypeData amiType = slaveTemplate.getAmiType();
        assertTrue(amiType.isMac());
        assertInstanceOf(MacData.class, amiType);
        final MacData macData = (MacData) amiType;
        assertEquals("sudo", macData.getRootCommandPrefix());
        assertEquals("sudo -u jenkins", macData.getSlaveCommandPrefix());
        assertEquals("-fakeFlag", macData.getSlaveCommandSuffix());
        assertEquals("22", macData.getSshPort());
        assertEquals("180", macData.getBootDelay());
    }

    @Test
    @ConfiguredWithCode("Mac.yml")
    void testMac(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("staging");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals("ami-5678", slaveTemplate.getAmi());
        assertEquals("/Users/jenkins", slaveTemplate.remoteFS);

        assertEquals("mac clear", slaveTemplate.getLabelString());
        assertEquals(2, slaveTemplate.getLabelSet().size());

        assertTrue(ec2Cloud.canProvision(new LabelAtom("clear")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("mac")));

        assertNull(slaveTemplate.spotConfig);
    }

    @Test
    @ConfiguredWithCode("MacData.yml")
    void testMacCloudConfigAsCodeExport(JenkinsConfiguredWithCodeRule j) throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        CNode clouds = Util.getJenkinsRoot(context).get("clouds");
        String exported = Util.toYamlString(clouds);
        String expected = Util.toStringFromYamlFile(this, "MacDataExport.yml");
        assertEquals(expected, exported);
    }

    @Test
    @ConfiguredWithCode("Unix-withEnclaveEnabled.yml")
    void testEnclaveEnabledConfigAsCodeExport(JenkinsConfiguredWithCodeRule j) {
        final EC2Cloud ec2Cloud = (EC2Cloud) Jenkins.get().getCloud("production");
        assertNotNull(ec2Cloud);
        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertTrue(slaveTemplate.getEnclaveEnabled());
    }
}
