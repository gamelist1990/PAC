package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.AirGravityWindow;
import org.pexserver.pac.movement.MotionCollisionSnapshot;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AirGravityWindowTest {
    @Test void jitteredCoordinatePacketsRemainSinglePhysicsSteps() {
        var window = new AirGravityWindow();
        double y = 64, dy = 0;
        long now = 1000;
        for (int tick = 0; tick < 30; tick++) {
            assertFalse(window.packet(true, true, y, 0.08, 0.98f, now));
            dy = (dy - 0.08) * 0.98f;
            y += dy;
            now += tick % 2 == 0 ? 83 : 17;
        }
    }

    @Test void positionlessFramesAreCountedAndIneligibleOnesBreakContinuity() {
        var window = new AirGravityWindow();
        double y = 80, dy = 0.42;
        for (int tick = 0; tick < 20; tick++) {
            assertFalse(window.packet(true, tick % 3 == 0, y, 0.08, 0.98f, 1000 + tick));
            dy = (dy - 0.08) * 0.98f;
            y += dy;
        }
        assertFalse(window.packet(false, false, y, 0.08, 0.98f, 1020));
        assertFalse(window.packet(true, true, 90, 0.08, 0.98f, 1021));
        assertTrue(window.diagnostic().contains("frames=0"));
    }

    @Test void packetClockStillDetectsFlightDuringBatchedDelivery() {
        var window = new AirGravityWindow();
        boolean flagged = false;
        for (int tick = 0; tick < 10; tick++)
            flagged |= window.packet(true, true, 80 + tick * .35, .08, .98f, 1000 + tick);
        assertTrue(flagged);
    }
    @Test void shortRiseAndOneSecondPauseIsCaughtDuringFirstPause() {
        var window = new AirGravityWindow();
        double y = 80;
        boolean detected = false;
        int detectedTick = -1;
        for (int tick = 0; tick <= 12; tick++) {
            if (tick % 20 == 0) y += 0.35;
            double observed = y - (tick % 70 == 0 ? 0.035 : 0);
            if (window.sample(true, observed, 0.08, 0.98f, 1000 + tick * 50L)) {
                detected = true;
                if (detectedTick < 0) detectedTick = tick;
            }
        }
        assertTrue(detected, "must detect before the next one-second ascent");
        assertTrue(detectedTick <= 4, "hard gravity mismatch should correct within 200ms");
    }

    @Test void vanillaJumpApexAndFallFitOneContinuousTrajectory() {
        var window = new AirGravityWindow();
        double y = 80, velocity = 0.42;
        for (int tick = 0; tick < 80; tick++) {
            assertFalse(window.sample(true, y, 0.08, 0.98f, 1000 + tick * 50L));
            velocity = (velocity - 0.08) * 0.98f;
            y += velocity;
        }
    }

    @Test void positionQuantizationAtJumpApexDoesNotFlag() {
        var window = new AirGravityWindow();
        double y = 80, sent = y, velocity = 0.42;
        for (int tick = 0; tick < 80; tick++) {
            if (Math.abs(y - sent) > 0.03) sent = y;
            assertFalse(window.sample(true, sent, 0.08, 0.98f, 1000 + tick * 50L));
            velocity = (velocity - 0.08) * 0.98f;
            y += velocity;
        }
    }

    @Test void groundCeilingAndStaleCollisionVolumesAreExcluded() {
        var floor = new MotionCollisionSnapshot(-3, 62, -3, 3, 70, 3,
                0.6, 1.8, 0.6, 0.6,
                List.of(new MotionCollisionSnapshot.Box(-3, 63, -3, 3, 64, 3)), true, 1000);
        assertFalse(AirGravityWindow.clearVerticalSweep(floor, 0, 64.1, 0, 1000));
        assertTrue(AirGravityWindow.clearVerticalSweep(floor, 0, 65, 0, 1000));
        assertFalse(AirGravityWindow.clearVerticalSweep(floor, 0, 65, 0, 1300));
        var ceiling = new MotionCollisionSnapshot(-3, 62, -3, 3, 70, 3,
                0.6, 1.8, 0.6, 0.6,
                List.of(new MotionCollisionSnapshot.Box(-3, 66, -3, 3, 67, 3)), true, 1000);
        assertFalse(AirGravityWindow.clearVerticalSweep(ceiling, 0, 64.1, 0, 1000));
    }

    @Test void horizontalDashChecksVerticalClearanceAlongWholeSegment() {
        var obstacle = new MotionCollisionSnapshot(-3, 62, -3, 4, 70, 3,
                0.6, 1.8, 0.6, 0.6,
                List.of(new MotionCollisionSnapshot.Box(0.9, 67.9, -0.2, 1.1, 68.2, 0.2)),
                true, 1000);
        assertTrue(AirGravityWindow.clearVerticalSweep(obstacle, 0, 66, 0, 1000));
        assertTrue(AirGravityWindow.clearVerticalSweep(obstacle, 2, 66, 0, 1000));
        assertFalse(AirGravityWindow.clearVerticalCorridor(obstacle,
                0, 66, 0, 2, 66, 0, 1000));
    }

    @Test void constantUpwardFlightCannotChooseANewInitialVelocityEveryTick() {
        var window = new AirGravityWindow();
        boolean detected = false;
        int detectedTick = -1;
        for (int tick = 0; tick <= 6; tick++)
            if (window.sample(true, 80 + tick * 0.35, 0.08, 0.98f, 1000 + tick * 50L)) {
                detected = true;
                if (detectedTick < 0) detectedTick = tick;
            }
        assertTrue(detected);
        assertTrue(detectedTick <= 4);
    }

    @Test void oneSecondCoordinateIntervalsStillAccumulateGravityEvidence() {
        var window = new AirGravityWindow();
        assertFalse(window.sample(true, 80.0, 0.08, 0.98f, 1000, 20));
        assertFalse(window.sample(true, 80.35, 0.08, 0.98f, 2000, 20));
        assertTrue(window.sample(true, 80.70, 0.08, 0.98f, 3000, 20),
                "one-second intervals must retain the trajectory and reject sustained ascent");
    }

    @Test void tinyInitialVelocityContradictionDoesNotFlagVanillaTransitionNoise() {
        var window = new AirGravityWindow();
        double y = 68.0013;
        double velocity = 0.26;
        assertFalse(window.sample(true, y, 0.08, 0.98f, 1_000));

        for (int tick = 1; tick <= 7; tick++) {
            velocity = (velocity - 0.08) * 0.98f;
            y += velocity;
            double observed = tick == 7 ? y + 0.14 : y;
            assertFalse(window.sample(true, observed, 0.08, 0.98f, 1_000 + tick * 50L),
                    "a tiny fitted initial-velocity contradiction must not become a hard Flight finding");
        }
    }

    @Test void lagAndIneligibleTransitionsDiscardTrajectory() {
        var window = new AirGravityWindow();
        for (int i = 0; i < 40; i++) {
            assertFalse(window.sample(i % 3 != 0, 80, 0.08, 0.98f, 1000 + i * 50L));
        }
        assertFalse(window.sample(true, 80, 0.08, 0.98f, 5000));
    }
}
