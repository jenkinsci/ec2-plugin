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
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("");
        assertNotNull(ec2Cloud);
        assertEquals(0, ec2Cloud.getTemplates().size());
    }

    @Test
    @ConfiguredWithCode("UnixData.yml")
    public void testUnixData() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("production");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<AgentTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final AgentTemplate agentTemplate = templates.get(0);
        assertEquals("ami-12345", agentTemplate.getAmi());
        assertEquals("/home/ec2-user", agentTemplate.remoteFS);

        assertEquals("linux ubuntu", agentTemplate.getLabelString());
        assertEquals(2, agentTemplate.getLabelSet().size());

        assertTrue(ec2Cloud.canProvision(new LabelAtom("ubuntu")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("linux")));

        final SpotConfiguration spotConfig = agentTemplate.spotConfig;
        assertNotEquals(null, spotConfig);
        assertTrue(spotConfig.getFallbackToOndemand());
        assertEquals(3, spotConfig.getSpotBlockReservationDuration());
        assertEquals("0.15", spotConfig.getSpotMaxBidPrice());
        assertTrue(spotConfig.useBidPrice);


        final AMITypeData amiType = agentTemplate.getAmiType();
        assertTrue(amiType.isUnix());
        assertTrue(amiType instanceof UnixData);
        final UnixData unixData = (UnixData) amiType;
        assertEquals("sudo", unixData.getRootCommandPrefix());
        assertEquals("sudo -u jenkins", unixData.getAgentCommandPrefix());
        assertEquals("-fakeFlag", unixData.getAgentCommandSuffix());
        assertEquals("22", unixData.getSshPort());
        assertEquals("180", unixData.getBootDelay());
    }

    @Test
    @ConfiguredWithCode("Unix.yml")
    public void testUnix() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("staging");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<AgentTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final AgentTemplate agentTemplate = templates.get(0);
        assertEquals("ami-5678", agentTemplate.getAmi());
        assertEquals("/mnt/jenkins", agentTemplate.remoteFS);

        assertEquals("linux clear", agentTemplate.getLabelString());
        assertEquals(2, agentTemplate.getLabelSet().size());

        assertTrue(ec2Cloud.canProvision(new LabelAtom("clear")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("linux")));

        assertNull(agentTemplate.spotConfig);
    }

    @Test
    @ConfiguredWithCode("WindowsData.yml")
    public void testWindowsData() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("development");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<AgentTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final AgentTemplate agentTemplate = templates.get(0);
        assertEquals("ami-abc123", agentTemplate.getAmi());
        assertEquals("C:/jenkins", agentTemplate.remoteFS);

        assertEquals("windows vs2019", agentTemplate.getLabelString());
        assertEquals(2, agentTemplate.getLabelSet().size());

        assertTrue(ec2Cloud.canProvision(new LabelAtom("windows")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("vs2019")));

        final AMITypeData amiType = agentTemplate.getAmiType();
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

        final List<AgentTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final AgentTemplate agentTemplate = templates.get(0);
        assertEquals(ConnectionStrategy.PRIVATE_DNS,agentTemplate.connectionStrategy);
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

        final List<AgentTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final AgentTemplate agentTemplate = templates.get(0);
        assertEquals("ami-123456", agentTemplate.getAmi());
        assertEquals("/home/ec2-user", agentTemplate.remoteFS);

        assertEquals("linux ubuntu", agentTemplate.getLabelString());
        assertEquals(2, agentTemplate.getLabelSet().size());

        final MinimumNumberOfInstancesTimeRangeConfig timeRangeConfig = agentTemplate.getMinimumNumberOfInstancesTimeRangeConfig();
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

        final List<AgentTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(3, templates.size());

        AgentTemplate agentTemplate;
        List<EC2Filter> expectedFilters;

        agentTemplate = templates.get(0);
        assertEquals("ami-0123456789abcdefg", agentTemplate.ami);
        assertNull(agentTemplate.getAmiOwners());
        assertNull(agentTemplate.getAmiUsers());
        assertNull(agentTemplate.getAmiFilters());

        agentTemplate = templates.get(1);
        assertNull(agentTemplate.ami);
        assertEquals("self", agentTemplate.getAmiOwners());
        assertEquals("self", agentTemplate.getAmiUsers());
        expectedFilters = Arrays.asList(new EC2Filter("name", "foo-*"),
                                        new EC2Filter("architecture", "x86_64"));
        assertEquals(expectedFilters, agentTemplate.getAmiFilters());

        agentTemplate = templates.get(2);
        assertNull(agentTemplate.ami);
        assertNull(agentTemplate.getAmiOwners());
        assertNull(agentTemplate.getAmiUsers());
        expectedFilters = Collections.emptyList();
        assertEquals(expectedFilters, agentTemplate.getAmiFilters());
    }

    @Test
    @ConfiguredWithCode("MacData.yml")
    public void testMacData() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("production");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<AgentTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final AgentTemplate agentTemplate = templates.get(0);
        assertEquals("ami-12345", agentTemplate.getAmi());
        assertEquals("/Users/ec2-user", agentTemplate.remoteFS);

        assertEquals("mac metal", agentTemplate.getLabelString());
        assertEquals(2, agentTemplate.getLabelSet().size());

        assertTrue(ec2Cloud.canProvision(new LabelAtom("metal")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("mac")));

        final AMITypeData amiType = agentTemplate.getAmiType();
        assertTrue(amiType.isMac());
        assertTrue(amiType instanceof MacData);
        final MacData macData = (MacData) amiType;
        assertEquals("sudo", macData.getRootCommandPrefix());
        assertEquals("sudo -u jenkins", macData.getAgentCommandPrefix());
        assertEquals("-fakeFlag", macData.getAgentCommandSuffix());
        assertEquals("22", macData.getSshPort());
        assertEquals("180", macData.getBootDelay());
    }

    @Test
    @ConfiguredWithCode("Mac.yml")
    public void testMac() throws Exception {
        final AmazonEC2Cloud ec2Cloud = (AmazonEC2Cloud) Jenkins.get().getCloud("staging");
        assertNotNull(ec2Cloud);
        assertTrue(ec2Cloud.isUseInstanceProfileForCredentials());

        final List<AgentTemplate> templates = ec2Cloud.getTemplates();
        assertEquals(1, templates.size());
        final AgentTemplate agentTemplate = templates.get(0);
        assertEquals("ami-5678", agentTemplate.getAmi());
        assertEquals("/Users/jenkins", agentTemplate.remoteFS);

        assertEquals("mac clear", agentTemplate.getLabelString());
        assertEquals(2, agentTemplate.getLabelSet().size());

        assertTrue(ec2Cloud.canProvision(new LabelAtom("clear")));
        assertTrue(ec2Cloud.canProvision(new LabelAtom("mac")));

        assertNull(agentTemplate.spotConfig);
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
