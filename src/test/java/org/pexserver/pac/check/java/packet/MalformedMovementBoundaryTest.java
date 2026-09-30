package org.pexserver.pac.check.java.packet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MalformedMovementBoundaryTest {
    @Test void malformedPayloadCannotResetOrPoisonPredictors() {
        assertTrue(InvalidMovementCheck.malformed(true, false, Double.NaN, 64, 0, 0, 0));
        assertTrue(InvalidMovementCheck.malformed(true, false, 0, Double.POSITIVE_INFINITY, 0, 0, 0));
        assertTrue(InvalidMovementCheck.malformed(true, false, 30_000_001, 64, 0, 0, 0));
        assertTrue(InvalidMovementCheck.malformed(false, true, 0, 0, 0, Float.NaN, 0));
        assertTrue(InvalidMovementCheck.malformed(false, true, 0, 0, 0, 0, 91));
    }
    @Test void absentCoordinatesInLookOnlyPacketsAreNotValidatedAsPositions() {
        assertFalse(InvalidMovementCheck.malformed(false, true, Double.NaN, Double.NaN, Double.NaN, 90, 0));
        assertFalse(InvalidMovementCheck.malformed(true, false, 0, 64, 0, Float.NaN, Float.NaN));
        assertFalse(InvalidMovementCheck.malformed(true, true, 30_000_000, 64, 0, -720, -90));
    }
}
