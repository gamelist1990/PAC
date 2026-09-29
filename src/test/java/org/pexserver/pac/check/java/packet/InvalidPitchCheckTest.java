package org.pexserver.pac.check.java.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvalidPitchCheckTest {
    @Test void vanillaPitchEndpointsRemainValid() {
        assertFalse(InvalidPitchCheck.invalidRotation(0.0f, -90.0f));
        assertFalse(InvalidPitchCheck.invalidRotation(720.0f, 90.0f));
    }

    @Test void noPitchLimitCannotHideInFormerPointZeroOneDegreeSlack() {
        assertTrue(InvalidPitchCheck.invalidRotation(0.0f, 90.0001f));
        assertTrue(InvalidPitchCheck.invalidRotation(0.0f, -90.0001f));
    }

    @Test void nonFiniteRotationIsAlwaysRejected() {
        assertTrue(InvalidPitchCheck.invalidRotation(Float.NaN, 0.0f));
        assertTrue(InvalidPitchCheck.invalidRotation(0.0f, Float.POSITIVE_INFINITY));
    }

    @Test void largeFiniteYawIsLegalBecauseVanillaYawWraps() {
        assertFalse(InvalidPitchCheck.invalidRotation(10_000.0f, 0.0f));
    }
}
