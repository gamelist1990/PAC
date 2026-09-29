package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovingSupportWindowTest {
    @Test void dismountTailExpiresWithoutMoreContact() {
        var window = new MovingSupportWindow();
        assertFalse(window.uncertain(1000));
        window.contact(1000, 19);
        assertTrue(window.uncertain(1299));
        assertFalse(window.uncertain(1300));
    }

    @Test void repeatedBoardingRefreshesOnlyFromRealContact() {
        var window = new MovingSupportWindow();
        for (int tick = 0; tick < 20; tick++) {
            window.contact(1000 + tick * 59, 19);
            assertTrue(window.uncertain(1000 + tick * 59 + 58));
        }
        assertFalse(window.uncertain(1000 + 19 * 59 + 300));
    }

    @Test void highLatencyTailIsBoundedAndCannotBeShortenedByAnotherContact() {
        var window = new MovingSupportWindow();
        window.contact(1000, Integer.MAX_VALUE);
        window.contact(1100, 0);
        assertTrue(window.uncertain(1999));
        assertFalse(window.uncertain(2000));
    }

    @Test void contactBreaksGravityFitAndItResumesAfterLeavingBoat() {
        var window = new MovingSupportWindow();
        var gravity = new AirGravityWindow();
        for (int tick = 0; tick < 20; tick++) {
            long now = 1000 + tick * 50;
            if (tick < 15) window.contact(now, 19);
            assertFalse(gravity.packet(!window.uncertain(now), true, 63.0858, .08, .98f, now));
        }
        boolean detected = false;
        for (int tick = 20; tick < 30; tick++)
            detected |= gravity.packet(!window.uncertain(1000 + tick * 50), true,
                    63.0858, .08, .98f, 1000 + tick * 50);
        assertTrue(detected, "unsupported hovering is checked again once contact ends");
    }
}
