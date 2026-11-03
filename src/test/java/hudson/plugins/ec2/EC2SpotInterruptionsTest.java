package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A build finds out it has lost its agent well after the agent is gone, so the reason has to be
 * readable by name for a while afterwards.
 */
class EC2SpotInterruptionsTest {

    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2020-01-01T00:00:00Z");
        EC2SpotInterruptions.clock = Clock.fixed(now, ZoneOffset.UTC);
        EC2SpotInterruptions.reset();
    }

    @AfterEach
    void tearDown() {
        EC2SpotInterruptions.clock = Clock.systemUTC();
        EC2SpotInterruptions.reset();
    }

    @Test
    void testAnAgentIsRememberedByTheNameABuildKnowsItBy() {
        EC2SpotInterruptions.note("EC2 (cloud) - medium (i-123)");

        assertTrue(EC2SpotInterruptions.wasInterrupted("EC2 (cloud) - medium (i-123)"));
    }

    @Test
    void testAnAgentThatWasNotReclaimedIsNotClaimedToHaveBeen() {
        EC2SpotInterruptions.note("one");

        assertFalse(EC2SpotInterruptions.wasInterrupted("another"));
    }

    /**
     * The record only has to outlast the gap between the instance going and the build noticing, and
     * a name that comes round again on a later agent must not inherit the answer.
     */
    @Test
    void testTheRecordIsForgottenOnceNoBuildCouldStillBeAsking() {
        EC2SpotInterruptions.note("gone");
        assertTrue(EC2SpotInterruptions.wasInterrupted("gone"));

        advance(Duration.ofMillis(TimeUnit.HOURS.toMillis(1) + 1));

        assertFalse(EC2SpotInterruptions.wasInterrupted("gone"));
    }

    /**
     * A controller losing capacity faster than its builds notice must not grow this without bound.
     * What it gives up is the oldest record, whose build is the least likely to still be asking.
     */
    @Test
    void testTheOldestRecordsAreGivenUpRatherThanGrowingWithoutBound() {
        int cap = 1000;
        for (int i = 0; i <= cap; i++) {
            EC2SpotInterruptions.note("agent-" + i);
            advance(Duration.ofMillis(1));
        }

        assertFalse(EC2SpotInterruptions.wasInterrupted("agent-0"), "the oldest record should have been given up");
        assertTrue(EC2SpotInterruptions.wasInterrupted("agent-" + cap), "the newest record should have been kept");
    }

    private void advance(Duration by) {
        now = now.plus(by);
        EC2SpotInterruptions.clock = Clock.fixed(now, ZoneOffset.UTC);
    }
}
