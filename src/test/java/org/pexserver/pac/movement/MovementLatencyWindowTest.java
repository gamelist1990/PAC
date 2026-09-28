package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.packet.ExternalMotionTracker;

import static org.junit.jupiter.api.Assertions.*;

class MovementLatencyWindowTest {
    @Test void jitterRecoveryPreservesAuthorizedMomentum() {
        var envelope = new SustainedSpeedEnvelope();
        envelope.serverVelocity(.7, 0, 64, 0, 1_000_000_000L);
        envelope.suspend(.2, 64, 0, 1_400_000_000L);
        var environment = new MotionEnvironment.Snapshot(false, true, false, false,
                0, .1, .8, 64, 0, 1, System.currentTimeMillis());
        var sample = envelope.accept(.8, 64, 0, environment, null, 1_450_000_000L, false);
        assertTrue(sample.evaluated());
        assertTrue(sample.legalSpeed() > .6);
        assertFalse(sample.flagged());
    }

    @Test void stableHighPingDoesNotExemptOrdinaryMovement() {
        var window = new MovementLatencyWindow();
        for (long now = 1_000; now < 5_000; now += 50)
            assertFalse(window.uncertain(now, 800, null));
    }

    @Test void responseWaitsForFullRoundTripAndSchedulingMargin() {
        for (int ping : new int[] {0, 50, 200, 500, 1_000}) {
            var window = new MovementLatencyWindow();
            var impulse = new ExternalMotionTracker.Impulse(1, .689, .4, .095, 1_000, false);
            assertTrue(window.uncertain(1_000, ping, impulse));
            for (long now = 1_050; now < 1_000 + ping + 100; now += 50)
                assertTrue(window.uncertain(now, ping, null), "in-flight at ping=" + ping);
            assertFalse(window.uncertain(1_000 + ping + 100, ping, impulse));
        }
    }

    @Test void delayedBatchHasFixedRecoveryDeadline() {
        var window = new MovementLatencyWindow();
        assertFalse(window.uncertain(1_000, 300, null));
        assertTrue(window.uncertain(1_300, 300, null));
        for (long now = 1_301; now < 1_450; now++)
            assertTrue(window.uncertain(now, 300, null));
        assertFalse(window.uncertain(1_450, 300, null));
        // Artificially spaced packets cannot perpetually refresh the exemption.
        assertFalse(window.uncertain(1_550, 300, null));
        assertFalse(window.uncertain(1_650, 300, null));
    }

    @Test void extremePingAndRepeatedImpulseHaveBoundedDeadline() {
        var window = new MovementLatencyWindow();
        var impulse = new ExternalMotionTracker.Impulse(1, .689, .4, .095, 1_000, false);
        for (long now = 1_000; now < 2_500; now += 50)
            assertTrue(window.uncertain(now, Integer.MAX_VALUE, impulse));
        assertFalse(window.uncertain(2_500, Integer.MAX_VALUE, impulse));
    }

    @Test void simulateDelayedKnockbackThenDetectPersistentSpeed() {
        for (int ping : new int[] {50, 200, 500, 1_000}) {
            var window = new MovementLatencyWindow();
            var envelope = new SustainedSpeedEnvelope();
            var impulse = new ExternalMotionTracker.Impulse(1, .689, .4, .095, 1_000, false);
            double speed = Math.hypot(impulse.x(), impulse.z());
            double x = 0;
            boolean caught = false;
            for (int tick = 0; tick < 120; tick++) {
                long now = 1_000 + tick * 50L;
                // Ordered TCP movement generated before the velocity arrives,
                // followed by the delayed launch and its naturally decaying tail.
                double actual = now < 1_000 + ping ? .225
                        : speed * Math.pow(.91, (now - 1_000 - ping) / 50.0);
                if (tick >= 70) actual = .8;
                x += actual;
                if (window.uncertain(now, ping, tick == 0 ? impulse : null)) {
                    envelope.serverVelocity(speed, x, 64, 0, now * 1_000_000L);
                    continue;
                }
                var environment = new MotionEnvironment.Snapshot(false, true, false, false,
                        0, .1, x, 64, 0, tick, System.currentTimeMillis());
                var sample = envelope.accept(x, 64, 0, environment, null, now * 1_000_000L, false);
                if (tick < 70) assertFalse(sample.flagged(), "legal delayed tail, ping=" + ping);
                else caught |= sample.flagged();
            }
            assertTrue(caught, "speed must still be caught after recovery, ping=" + ping);
        }
    }
}
