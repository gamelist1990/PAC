package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.AirHoverWindow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirHoverWindowTest {
    @Test void terrainEligibilityMustNotJoinSeparateJumpApexesIntoHover() {
        var window = new AirHoverWindow();
        double y = 64, velocity = 0;
        for (int tick = 0; tick < 200; tick++) {
            velocity = y == 64 ? 0.42f : (velocity - 0.08) * 0.98f;
            y = Math.max(64, y + velocity);
            // Near terrain only the top of each jump may have clear air.
            assertFalse(window.sample(y > 65, y, 1000 + tick * 50L),
                    "separate jump apexes are not stationary flight: " + tick);
        }
    }
    @Test void stationaryFlightIsDetectedFromRepeatedSamples() {
        var window = new AirHoverWindow();
        assertFalse(window.sample(true, 80, 1_000));
        assertFalse(window.sample(true, 80, 2_000));
        assertFalse(window.sample(true, 80, 3_000));
        assertTrue(window.sample(true, 80, 4_000));
    }

    @Test void wurstAntiKickSizedVerticalOscillationDoesNotEraseHoverEvidence() {
        var window = new AirHoverWindow();
        long start = 1_000;
        boolean detected = false;
        for (int tick = 0; tick <= 60; tick++) {
            // Wurst's default Anti-Kick periodically dips and restores by 0.035 blocks.
            double y = 80 + (tick % 20 == 0 ? -0.035 : tick % 20 == 1 ? 0 : 0);
            detected |= window.sample(true, y, start + tick * 50L);
        }
        assertTrue(detected);
    }

    @Test void largerConfiguredAntiKickOscillationStillFitsTheEnvelope() {
        var window = new AirHoverWindow();
        long start = 1_000;
        boolean detected = false;
        for (int tick = 0; tick <= 60; tick++) {
            double y = 80 + (tick % 10 == 0 ? -0.2 : tick % 10 == 1 ? 0 : 0);
            detected |= window.sample(true, y, start + tick * 50L);
        }
        assertTrue(detected);
    }

    @Test void wurstSeventyTickAntiKickCannotResetOnGeometryFlicker() {
        var window = new AirHoverWindow();
        long start = 1_000;
        boolean detected = false;
        for (int tick = 0; tick <= 150; tick++) {
            int phase = tick % 70;
            double y = 80 + (phase == 0 ? -0.035 : 0);
            // The tiny down/up pair can briefly make the main-thread snapshot
            // change between ground and vertical-air classification.
            boolean eligible = phase != 0 && phase != 1;
            detected |= window.sample(eligible, y, start + tick * 50L);
        }
        assertTrue(detected,
                "Wurst Flight's 70-tick, 0.035m Anti-Kick cycle must preserve hover evidence");
    }

    @Test void repeatedFlightOnOffPeriodsCannotHideBehindTheFallSegments() {
        var window = new AirHoverWindow();
        double y = 80;
        double verticalVelocity = 0;
        long start = 1_000;
        boolean detected = false;
        for (int tick = 0; tick <= 120; tick++) {
            boolean flightEnabled = tick % 40 < 20;
            if (flightEnabled) verticalVelocity = 0;
            else {
                verticalVelocity = (verticalVelocity - 0.08) * 0.98;
                y += verticalVelocity;
            }
            detected |= window.sample(true, y, start + tick * 50L);
        }
        assertTrue(detected);
    }

    @Test void legitimateJumpHeightAndUntrustedMovementDoNotBuildHoverEvidence() {
        var window = new AirHoverWindow();
        for (int tick = 0; tick <= 60; tick++) {
            int phase = tick % 40;
            double y = 80 + (phase < 20 ? phase * 0.06 : (40 - phase) * 0.06);
            assertFalse(window.sample(true, y, 1_000 + tick * 50L));
        }

        assertFalse(window.sample(false, 80, 5_000));
        for (int tick = 0; tick <= 40; tick++)
            assertFalse(window.sample(true, 80, 6_000 + tick * 50L));
    }
}
