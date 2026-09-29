package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoatFlightCheckTest {
    @Test void sameDriverShortRehookPreservesContinuity() {
        assertTrue(BoatFlightCheck.preserveShortRehookGap(2, true, true));
        assertTrue(BoatFlightCheck.preserveShortRehookGap(4, true, true));
    }

    @Test void longGapDriverSwapOrWorldChangeStartsNewRide() {
        assertFalse(BoatFlightCheck.preserveShortRehookGap(1, true, true));
        assertFalse(BoatFlightCheck.preserveShortRehookGap(5, true, true));
        assertFalse(BoatFlightCheck.preserveShortRehookGap(2, false, true));
        assertFalse(BoatFlightCheck.preserveShortRehookGap(2, true, false));
    }

    @Test void airSpeedEvidenceSurvivesAnUnobservedRehookGap() {
        var window = new BoatFlightCheck.AirSpeedWindow();

        assertFalse(window.sample(true, 0.80));
        assertTrue(window.sample(true, 0.80));
        int beforeGap = window.fastTicks();

        // Rehook continuity intentionally skips the aggregate movement sample
        // while leaving the pre-gap evidence intact.
        assertTrue(BoatFlightCheck.preserveShortRehookGap(2, true, true));
        assertTrue(window.sample(true, 0.80));
        assertTrue(window.fastTicks() > beforeGap);
    }

    @Test void resetStillClearsBoatSpeedEvidenceForANewRide() {
        var window = new BoatFlightCheck.AirSpeedWindow();
        window.sample(true, 0.80);
        window.sample(true, 0.80);
        assertTrue(window.fastTicks() > 0);

        window.reset();

        assertFalse(window.sample(true, 0.80));
        assertTrue(window.fastTicks() == 0);
    }
}
