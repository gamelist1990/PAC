package org.pexserver.pac.check.java.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.AirHoverWindow;
import org.pexserver.pac.movement.MotionEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirPredictionHoverEligibilityTest {
    @Test void unsupportedNearGroundAirStillRunsHoverDetection() {
        long now = 1_000;
        var environment = snapshot(64.035, now, true, 1.0f);

        assertFalse(environment.verticalAir(),
                "near-floor geometry can be too close for the strict free-fall corridor");
        assertTrue(AirPredictionCheck.flightHoverEligible(environment, true, now),
                "normal-gravity unsupported air must still be eligible for sustained hover detection");
    }

    @Test void cobwebSlowdownDoesNotBecomeFlightHoverEvidence() {
        long now = 1_000;
        var environment = snapshot(64.2, now, true, 0.05f);

        assertFalse(AirPredictionCheck.flightHoverEligible(environment, true, now));
    }

    @Test void wurstSeventyTickNearGroundAntiKickIsDetectedWhileEnabled() {
        var window = new AirHoverWindow();
        long start = 1_000;
        boolean detected = false;

        for (int tick = 0; tick <= 150; tick++) {
            int phase = tick % 70;
            boolean airborne = phase != 0;
            double y = airborne ? 64.035 : 64.0;
            long now = start + tick * 50L;
            var environment = snapshot(y, now, airborne, 1.0f);
            boolean eligible = AirPredictionCheck.flightHoverEligible(environment, true, now);
            detected |= window.sample(eligible, y, now);
        }

        assertTrue(detected,
                "Wurst Flight's 0.035m Anti-Kick above a floor must flag before the hack is disabled");
    }

    private static MotionEnvironment.Snapshot snapshot(double y, long now,
                                                       boolean gravityAirborne,
                                                       float stuckVerticalMultiplier) {
        return new MotionEnvironment.Snapshot(
                false, false, true, false, false,
                0, 0.13, 0, y, 0, (int) (now / 50L), now,
                false, false, 0.6f,
                0.08, 0.91f, 0.98f, 0.42f,
                false, -1, false,
                0.3f, 1.0f, 1.0f, stuckVerticalMultiplier,
                gravityAirborne);
    }
}
