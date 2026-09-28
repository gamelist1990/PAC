package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoatAirVerticalWindowTest {
    @Test
    void iceLaunchFollowsVanillaGravityWithoutFlightEvidence() {
        BoatFlightCheck.AirVerticalWindow window = new BoatFlightCheck.AirVerticalWindow();
        assertFalse(window.sample(false, 0));

        double dy = 0.26;
        for (int tick = 0; tick < 12; tick++) {
            assertFalse(window.sample(true, dy));
            dy -= 0.04;
        }
    }

    @Test
    void repeatedBoatFlyJumpInputIsDetectedWhileHorizontalSpeedIsNormal() {
        BoatFlightCheck.AirVerticalWindow window = new BoatFlightCheck.AirVerticalWindow();
        window.sample(false, 0);
        assertFalse(window.sample(true, 0.26));
        assertFalse(window.sample(true, -0.04));
        assertFalse(window.sample(true, -0.04));
        assertFalse(window.sample(true, 0.26));
        assertFalse(window.sample(true, -0.04));
        assertFalse(window.sample(true, -0.04));
        assertFalse(window.sample(true, 0.26));
        assertFalse(window.sample(true, -0.04));
        assertFalse(window.sample(true, -0.04));
        assertTrue(window.sample(true, 0.26));
    }

    @Test
    void oneAirImpulseDoesNotBecomeBoatFlyEvidence() {
        BoatFlightCheck.AirVerticalWindow window = new BoatFlightCheck.AirVerticalWindow();
        window.sample(false, 0);
        window.sample(true, -0.12);
        assertFalse(window.sample(true, 0.26));
        double dy = 0.22;
        for (int tick = 0; tick < 10; tick++) {
            assertFalse(window.sample(true, dy));
            dy -= 0.04;
        }
    }

    @Test
    void holdingBoatFlyJumpBuildsEvidenceBeforeHoverThreshold() {
        BoatFlightCheck.AirVerticalWindow window = new BoatFlightCheck.AirVerticalWindow();
        window.sample(false, 0);
        assertFalse(window.sample(true, 0.26));
        assertFalse(window.sample(true, 0.26));
        assertFalse(window.sample(true, 0.26));
        assertTrue(window.sample(true, 0.26));
    }
}
