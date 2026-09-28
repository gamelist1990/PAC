package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.AirPredictor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirPredictorTest {
    @Test void freeFallFollowsGravityThenAirDrag() {
        double first = AirPredictor.nextDisplacement(0.42);
        double second = AirPredictor.nextDisplacement(first);
        assertEquals((0.42 - 0.08) * 0.98f, first, 1e-12);
        assertEquals((first - 0.08) * 0.98f, second, 1e-12);
        assertEquals(0, AirPredictor.offset(first, second), 1e-12);
    }

    @Test void sustainedHoverIsOutsideOrdinaryAirEnvelope() {
        assertTrue(AirPredictor.offset(0, 0) > 0.06);
    }

    @Test void changedGravityAndDragUseAttributeValues() {
        double next = AirPredictor.nextDisplacement(0.2, 0.04, 0.96f);
        assertEquals((0.2 - 0.04) * 0.96f, next, 1e-12);
        assertEquals(0, AirPredictor.offset(0.2, next, 0.04, 0.96f), 1e-12);
    }

    @Test void slowFallingChangesGravityOnlyAfterVerticalVelocityTurnsDownward() {
        assertEquals((0.2 - 0.08) * 0.98f,
                AirPredictor.nextDisplacement(0.2, 0.08, 0.98f, true), 1e-12);
        assertEquals((-0.1 - 0.01) * 0.98f,
                AirPredictor.nextDisplacement(-0.1, 0.08, 0.98f, true), 1e-12);
        assertEquals((-0.1 - 0.005) * 0.98f,
                AirPredictor.nextDisplacement(-0.1, 0.005, 0.98f, true), 1e-12);
    }

    @Test void levitationEasesVelocityTowardEffectLevelBeforeDrag() {
        double expected = (-0.1 + (0.1 - (-0.1)) * 0.2) * 0.98f;
        assertEquals(expected,
                AirPredictor.nextDisplacement(-0.1, 0.08, 0.98f, false, 1), 1e-12);
        assertEquals(expected,
                AirPredictor.nextDisplacement(-0.1, 0.08, 0.98f, true, 1), 1e-12);
    }
}
