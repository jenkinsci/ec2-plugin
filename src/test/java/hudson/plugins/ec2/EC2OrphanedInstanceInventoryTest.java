package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

class EC2OrphanedInstanceInventoryTest {

    private static Instance instance(String id, Instant launched, String slaveTypeTag) {
        return instance(id, launched, slaveTypeTag, false);
    }

    private static Instance instance(String id, Instant launched, String slaveTypeTag, boolean carriedAnAgent) {
        Instance instance = mock(Instance.class);
        when(instance.instanceId()).thenReturn(id);
        when(instance.launchTime()).thenReturn(launched);
        List<Tag> tags = new ArrayList<>();
        if (slaveTypeTag != null) {
            tags.add(Tag.builder()
                    .key(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)
                    .value(slaveTypeTag)
                    .build());
        }
        if (carriedAnAgent) {
            tags.add(Tag.builder()
                    .key(EC2Tag.TAG_NAME_JENKINS_AGENT_CONNECTED)
                    .value(Instant.now().toString())
                    .build());
        }
        when(instance.tags()).thenReturn(tags);
        return instance;
    }

    private static EC2Cloud cloudWith(SlaveTemplate... templates) {
        EC2Cloud cloud = mock(EC2Cloud.class);
        when(cloud.getTemplates()).thenReturn(List.of(templates));
        return cloud;
    }

    @Test
    void instanceStillWithinAttachGraceIsNotOrphaned() {
        Instance booting = instance("i-booting", Instant.now(), null);

        assertFalse(EC2OrphanedInstanceInventory.isPastAttachGrace(booting));
        assertFalse(EC2OrphanedInstanceInventory.isUnusableOrphan(cloudWith(), booting, Set.of()));
    }

    @Test
    void attachedInstanceIsNeverUnusable() {
        Instance attached = instance("i-attached", Instant.now().minus(2, ChronoUnit.HOURS), null);

        assertFalse(EC2OrphanedInstanceInventory.isUnusableOrphan(cloudWith(), attached, Set.of("i-attached")));
    }

    @Test
    void orphanWithNoSurvivingTemplateIsUnusable() {
        Instance orphan = instance("i-orphan", Instant.now().minus(2, ChronoUnit.HOURS), "demand_gone");

        assertTrue(EC2OrphanedInstanceInventory.isUnusableOrphan(cloudWith(), orphan, Set.of()));
    }

    @Test
    void orphanItsTemplateWouldAdoptStillCountsAsCapacity() {
        SlaveTemplate template = mock(SlaveTemplate.class);
        when(template.getDescription()).thenReturn("linux");
        when(template.isAvoidUsingOrphanedNodes()).thenReturn(false);

        Instance orphan = instance(
                "i-adoptable",
                Instant.now().minus(2, ChronoUnit.HOURS),
                EC2Cloud.getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_DEMAND, "linux"));

        assertFalse(EC2OrphanedInstanceInventory.isUnusableOrphan(cloudWith(template), orphan, Set.of()));
    }

    @Test
    void usedOrphanOfATemplateThatRefusesAdoptionIsUnusable() {
        SlaveTemplate template = mock(SlaveTemplate.class);
        when(template.getDescription()).thenReturn("linux");
        when(template.isAvoidUsingOrphanedNodes()).thenReturn(true);

        Instance orphan = instance(
                "i-refused",
                Instant.now().minus(2, ChronoUnit.HOURS),
                EC2Cloud.getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_DEMAND, "linux"),
                true);

        assertTrue(EC2OrphanedInstanceInventory.isUnusableOrphan(cloudWith(template), orphan, Set.of()));
    }

    /**
     * Refusing to re-use instances is about not taking back one that has already served an agent.
     * An instance that never carried one is capacity that was launched and never claimed, so it is
     * still adoptable and still worth its place in the caps.
     */
    @Test
    void unusedOrphanIsAdoptableEvenWhereTheTemplateRefusesAdoption() {
        SlaveTemplate template = mock(SlaveTemplate.class);
        when(template.getDescription()).thenReturn("linux");
        when(template.isAvoidUsingOrphanedNodes()).thenReturn(true);

        Instance orphan = instance(
                "i-never-used",
                Instant.now().minus(2, ChronoUnit.HOURS),
                EC2Cloud.getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_DEMAND, "linux"));

        assertFalse(EC2OrphanedInstanceInventory.isUnusableOrphan(cloudWith(template), orphan, Set.of()));
    }
}
