package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroundMotion3dContinuityTest {
    @Test void pointThreeFiveCannotBecomeTheNextTicksLegalVelocity() {
        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0, 0, 0, 0.35, environment(0, 0, 0, 1000), 1000);
        GroundMotionSequence.Sample sample = null;
        for (int tick = 1; tick <= 20; tick++) {
            long now = 1000 + tick * 50;
            sample = sequence.accept(true, true, 0, 64, tick * 0.35, 0,
                    environment(0, tick * 0.35, tick, now), now, null, null, floor(now));
            assertTrue(sample.evaluated());
        }
        assertTrue(sample.offset() > 0.06, "the XYZ replay must retain accumulated prediction error");
        assertTrue(sequence.motion().dz() < 0.29, "injected speed must not seed the next frame");
    }

    @Test void vanillaAccelerationBrakingAndTurnsRemainMatched() {
        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0, 0, 0, 0, environment(0, 0, 0, 1000), 1000);
        double x = 0, z = 0, vx = 0, vz = 0;
        for (int tick = 1; tick <= 60; tick++) {
            // Independent vanilla ground recurrence: drag, then 0.98 input.
            vx *= 0.6f * 0.91f;
            vz *= 0.6f * 0.91f;
            if (tick <= 20) vz += 0.13f * 0.98f;
            else if (tick <= 40) vx -= 0.13f * 0.98f;
            long now = 1000 + tick * 50;
            x += vx;
            z += vz;
            var sample = sequence.accept(true, true, x, 64, z, tick <= 20 ? 0 : 90,
                    environment(x, z, tick, now), now, null, null, floor(now));
            assertTrue(sample.evaluated());
            assertTrue(sample.offset() < 0.003, "vanilla tick " + tick + ": " + sample.offset());
        }
    }

    @Test void wallCollisionClearsBlockedVelocityInsteadOfKeepingTravelDistance() {
        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0.4, 0, 0, 0.28, environment(0, 0.4, 0, 1000), 1000);
        var wall = new MotionCollisionSnapshot(-20, 62, -20, 20, 70, 20,
                0.6, 1.8, 0.6, 0.6,
                List.of(new MotionCollisionSnapshot.Box(-20, 63, -20, 20, 64, 20),
                        new MotionCollisionSnapshot.Box(-20, 64, 0.8, 20, 68, 1.8)), true, 1050);
        var sample = sequence.accept(true, true, 0, 64, 0.5, 0,
                environment(0, 0.5, 1, 1050), 1050, null, null, wall);
        assertTrue(sample.evaluated());
        assertEquals(0, sample.offset(), 1.0e-7);
        assertEquals(0, sequence.motion().dz(), 1.0e-7);
    }

    private static MotionEnvironment.Snapshot environment(double x, double z, int tick, long now) {
        return new MotionEnvironment.Snapshot(true, false, true, false,
                0, 0.13, x, 64, z, tick, now);
    }

    private static MotionCollisionSnapshot floor(long now) {
        return new MotionCollisionSnapshot(-20, 62, -20, 20, 70, 20,
                0.6, 1.8, 0.6, 0.6,
                List.of(new MotionCollisionSnapshot.Box(-20, 63, -20, 20, 64, 20)), true, now);
    }
}
