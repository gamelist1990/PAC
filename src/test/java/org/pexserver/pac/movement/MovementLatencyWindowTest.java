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
            assertFalse(window.uncertain(now));
    }

    @Test void pingDoesNotSuspendOrderedMovementPrediction() {
        for (int ping : new int[] {0, 50, 200, 500, 1_000}) {
            var window = new MovementLatencyWindow();
            assertFalse(window.uncertain(1_000));
            for (long now = 1_050; now < 1_000 + ping + 100; now += 50)
                assertFalse(window.uncertain(now), "ordered movement at ping=" + ping);
            assertFalse(window.uncertain(1_000 + ping + 100));
        }
    }

    @Test void delayedBatchHasFixedRecoveryDeadline() {
        var window = new MovementLatencyWindow();
        assertFalse(window.uncertain(1_000));
        assertTrue(window.uncertain(1_300));
        for (long now = 1_301; now < 1_450; now++)
            assertTrue(window.uncertain(now));
        assertFalse(window.uncertain(1_450));
        // Artificially spaced packets cannot perpetually refresh the exemption.
        assertFalse(window.uncertain(1_550));
        assertFalse(window.uncertain(1_650));
    }

    @Test void extremePingDoesNotCreateMovementBlindWindow() {
        var window = new MovementLatencyWindow();
        for (long now = 1_000; now < 2_500; now += 50)
            assertFalse(window.uncertain(now));
        assertFalse(window.uncertain(2_500));
    }

    @Test void modeledServerVelocityDoesNotHideLaterPersistentSpeed() {
        var window = new MovementLatencyWindow();
        var envelope = new SustainedSpeedEnvelope();
        var impulse = new ExternalMotionTracker.Impulse(1, .689, .4, .095, 1_000, false);
        double speed = Math.hypot(impulse.x(), impulse.z());
        double x = 0;
        long start = 1_000_000_000L;
        envelope.serverVelocity(speed, x, 64, 0, start);
        boolean caught = false;
        for (int tick = 0; tick < 120; tick++) {
            long nowMillis = 1_000 + tick * 50L;
            long nowNanos = start + tick * 50_000_000L;
            double actual = tick < 70 ? speed * Math.pow(.91, tick) : .8;
            x += actual;
            assertFalse(window.uncertain(nowMillis));
            var environment = new MotionEnvironment.Snapshot(false, true, false, false,
                    0, .1, x, 64, 0, tick, System.currentTimeMillis());
            var sample = envelope.accept(x, 64, 0, environment, null, nowNanos, false);
            if (tick < 70) assertFalse(sample.flagged(), "legal server velocity tail");
            else caught |= sample.flagged();
        }
        assertTrue(caught, "persistent speed must be caught while prediction remains active");
    }
}
