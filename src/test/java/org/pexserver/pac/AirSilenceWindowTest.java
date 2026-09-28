package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.AirSilenceWindow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirSilenceWindowTest {
    @Test void sustainedAirborneSilenceFlagsAfterThreeSeconds() {
        var window = new AirSilenceWindow();
        window.packet(1000);
        assertFalse(window.sample(true, 1100));
        assertFalse(window.sample(true, 4000));
        assertTrue(window.sample(true, 4200));
        assertFalse(window.sample(true, 5000));
    }

    @Test void movementPacketAndLandingResetSilenceWindow() {
        var window = new AirSilenceWindow();
        window.packet(1000);
        window.sample(true, 1100);
        window.packet(3000);
        assertFalse(window.sample(true, 4200));
        assertFalse(window.sample(false, 7000));
        assertFalse(window.sample(true, 7100));
    }
}
