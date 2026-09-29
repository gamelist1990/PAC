package org.pexserver.pac.check.java.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.MotionEnvironment;
import org.pexserver.pac.movement.MotionPredictor;
import org.pexserver.pac.packet.JavaInputCapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionPredictionSprintEligibilityTest {
    private static MotionEnvironment.Snapshot walking() {
        return new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, 0, 64, 0, 1, System.currentTimeMillis());
    }

    private static JavaInputCapture.Window sprintInput() {
        return new JavaInputCapture.Window(
                new MotionPredictor.Input(true, false, false, false,
                        false, false, true),
                null);
    }

    @Test void hungryPlayerDoesNotGetClientSprintSpeedInjected() {
        var result = MotionPredictionCheck.clientSprintEnvironment(walking(), sprintInput(), false);

        assertFalse(result.sprinting());
        assertEquals(0.1, result.movementSpeed(), 1.0e-9);
    }

    @Test void eligibleSprintInputStillBridgesServerSnapshotDelay() {
        var result = MotionPredictionCheck.clientSprintEnvironment(walking(), sprintInput(), true);

        assertTrue(result.sprinting());
        assertEquals(0.13, result.movementSpeed(), 1.0e-9);
    }
}
