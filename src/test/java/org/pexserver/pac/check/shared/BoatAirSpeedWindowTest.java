package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoatAirSpeedWindowTest {
    @Test
    void iceLaunchKeepsItsVanillaMomentumWithoutFastFlightEvidence() {
        BoatFlightCheck.AirSpeedWindow window = new BoatFlightCheck.AirSpeedWindow();
        double horizontal = 1.346;
        for (int tick = 0; tick < 32; tick++) {
            assertFalse(window.sample(false, horizontal));
        }

        for (int tick = 0; tick < 20; tick++) {
            horizontal = horizontal * 0.9 + 0.04;
            assertFalse(window.sample(true, horizontal));
            assertEquals(0, window.fastTicks());
        }
    }

    @Test
    void sustainedAirSpeedAboveVanillaFrictionBuildsEvidence() {
        BoatFlightCheck.AirSpeedWindow window = new BoatFlightCheck.AirSpeedWindow();
        window.sample(false, 1.346);
        assertFalse(window.sample(true, 1.346));

        for (int tick = 0; tick < 4; tick++) {
            assertTrue(window.sample(true, 1.346));
        }
        assertEquals(4, window.fastTicks());
    }
}
