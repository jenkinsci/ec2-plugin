package hudson.plugins.ec2;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
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

import hudson.plugins.ec2.util.MinimumNumberOfInstancesTimeRangeConfig;

import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class ConfigurationAsCodeTest {

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("EC2CloudEmpty.yml")
    public void testEmptyConfig() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("empty");
        assertNotNull(ec2Cloud);
        assertEquals(0, ec2Cloud.getTemplates().size());
    }

    @Test
    @ConfiguredWithCode("UnixData.yml")
    public void testUnixData() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("production");
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
        assertTrue(amiType instanceof UnixData);
        final UnixData unixData = (UnixData) amiType;
        assertEquals("sudo", unixData.getRootCommandPrefix());
        assertEquals("sudo -u jenkins", unixData.getSlaveCommandPrefix());
        assertEquals("-fakeFlag", unixData.getSlaveCommandSuffix());
        assertEquals("22", unixData.getSshPort());
        assertEquals("180", unixData.getBootDelay());
    }

    @Test
    @ConfiguredWithCode("Unix.yml")
    public void testUnix() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("staging");
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
    public void testWindowsData() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("development");
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
        assertTrue(amiType instanceof WindowsData);
        final WindowsData windowsData = (WindowsData) amiType;
        assertEquals(Secret.fromString("password"), windowsData.getPassword());
        assertTrue(windowsData.isUseHTTPS());
        assertEquals("180", windowsData.getBootDelay());
    }

    @Test
    @ConfiguredWithCode("BackwardsCompatibleConnectionStrategy.yml")
    public void testBackwardsCompatibleConnectionStrategy() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("us-east-1");
        assertNotNull(ec2Cloud);

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals(ConnectionStrategy.PRIVATE_DNS,slaveTemplate.connectionStrategy);
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

    @Test
    @ConfiguredWithCode("UnixData-withAltEndpointAndJavaPath.yml")
    public void testConfigAsCodeWithAltEndpointAndJavaPathExport() throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        CNode clouds = getJenkinsRoot(context).get("clouds");
        String exported = toYamlString(clouds);
        String expected = toStringFromYamlFile(this, "UnixDataExport-withAltEndpointAndJavaPath.yml");
        assertEquals(expected, exported);
    }

    @Test
    @ConfiguredWithCode("Unix-withMinimumInstancesTimeRange.yml")
    public void testConfigAsCodeWithMinimumInstancesTimeRange() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("timed");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<SlaveTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final SlaveTemplate slaveTemplate = templates.get(0);
        assertEquals("ami-123456", slaveTemplate.getAmi());
        assertEquals("/home/ec2-user", slaveTemplate.remoteFS);

        assertEquals("linux ubuntu", slaveTemplate.getLabelString());
        assertEquals(2, slaveTemplate.getLabelSet().size());

        final MinimumNumberOfInstancesTimeRangeConfig timeRangeConfig = slaveTemplate.getMinimumNumberOfInstancesTimeRangeConfig();
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
    @ConfiguredWithCode("Ami.yml")
    public void testAmi() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("test");
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
        expectedFilters = Arrays.asList(new EC2Filter("name", "foo-*"),
                                        new EC2Filter("architecture", "x86_64"));
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
    public void testMacData() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("production");
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
        assertTrue(amiType instanceof MacData);
        final MacData macData = (MacData) amiType;
        assertEquals("sudo", macData.getRootCommandPrefix());
        assertEquals("sudo -u jenkins", macData.getSlaveCommandPrefix());
        assertEquals("-fakeFlag", macData.getSlaveCommandSuffix());
        assertEquals("22", macData.getSshPort());
        assertEquals("180", macData.getBootDelay());
    }

    @Test
    @ConfiguredWithCode("Mac.yml")
    public void testMac() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("staging");
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
    public void testMacCloudConfigAsCodeExport() throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        CNode clouds = getJenkinsRoot(context).get("clouds");
        String exported = toYamlString(clouds);
        String expected = toStringFromYamlFile(this, "MacDataExport.yml");
        assertEquals(expected, exported);
    }
}
