package org.pexserver.pac.check.java.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvalidMovementCheckTest {
    @Test void rejectsNonFiniteCoordinates() {
        assertTrue(InvalidMovementCheck.invalidCoordinates(Double.NaN, 64, 0));
        assertTrue(InvalidMovementCheck.invalidCoordinates(0, Double.POSITIVE_INFINITY, 0));
        assertTrue(InvalidMovementCheck.invalidCoordinates(0, 64, Double.NEGATIVE_INFINITY));
    }

    @Test void rejectsExtremeFiniteCoordinatesOnEveryAxis() {
        assertTrue(InvalidMovementCheck.invalidCoordinates(30_000_001, 64, 0));
        assertTrue(InvalidMovementCheck.invalidCoordinates(0, 30_000_001, 0));
        assertTrue(InvalidMovementCheck.invalidCoordinates(0, 64, -30_000_001));
    }

    @Test void ordinaryAndBoundaryCoordinatesRemainValid() {
        assertFalse(InvalidMovementCheck.invalidCoordinates(0, 64, 0));
        assertFalse(InvalidMovementCheck.invalidCoordinates(30_000_000, -30_000_000, -30_000_000));
        assertFalse(InvalidMovementCheck.invalidCoordinates(Double.MIN_VALUE, 0, 0));
    }
}
