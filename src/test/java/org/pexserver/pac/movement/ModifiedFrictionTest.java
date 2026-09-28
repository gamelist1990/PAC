package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModifiedFrictionTest {
    @Test void matchesNmsModifierFormulaAndClamps() {
        assertEquals(0.91f, MotionEnvironment.modifiedFriction(0.91f, 1), 1e-7);
        assertEquals(0.955f, MotionEnvironment.modifiedFriction(0.91f, 0.5), 1e-7);
        assertEquals(0.99f, MotionEnvironment.modifiedFriction(0.98f, 0.5), 1e-7);
        assertEquals(1.0f, MotionEnvironment.modifiedFriction(0.91f, 0), 1e-7);
        assertEquals(0.0f, MotionEnvironment.modifiedFriction(0.6f, 4), 1e-7);
    }
}
