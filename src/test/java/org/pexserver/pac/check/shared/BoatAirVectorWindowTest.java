package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoatAirVectorWindowTest {
    @Test void iceLaunchCanSteerWithinVanillaInputEnvelope() {
        var window = new BoatFlightCheck.AirVectorWindow();
        double dx = 1.346;
        double dz = 0;
        assertFalse(window.sample(false, dx, dz));
        for (int tick = 0; tick < 20; tick++) {
            dx *= 0.9;
            dz = dz * 0.9 + 0.04;
            assertFalse(window.sample(true, dx, dz));
        }
    }

    @Test void repeatedSharpTurnsWhileFallingBecomeEvidence() {
        var window = new BoatFlightCheck.AirVectorWindow();
        assertFalse(window.sample(true, 0.35, 0));
        assertFalse(window.sample(true, 0, 0.35));
        assertFalse(window.sample(true, -0.35, 0));
        assertTrue(window.sample(true, 0, -0.35));
        assertTrue(window.steeringTicks() >= 3);
        assertFalse(window.sample(false, 0, 0));
        assertFalse(window.sample(true, 0.35, 0));
    }
}
