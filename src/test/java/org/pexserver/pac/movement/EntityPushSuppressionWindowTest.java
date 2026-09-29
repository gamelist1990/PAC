package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.packet.JavaInputCapture;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPushSuppressionWindowTest {
    private static final MotionPredictor.Input IDLE = new MotionPredictor.Input(
            false, false, false, false, false, false, false);
    private static final JavaInputCapture.Window IDLE_INPUT =
            new JavaInputCapture.Window(IDLE, null);

    @Test void repeatedIdleNoPushMatchesFlagClientSuppression() {
        var window = new EntityPushSuppressionWindow();
        var environment = ground(1_000);
        var collisions = push(0.05, 0, false);
        var previous = new MotionPredictor.Motion(0, 0, 0);

        for (int sample = 1; sample < 4; sample++) {
            var result = window.accept(previous, 0, 0,
                    0, 64, 0, environment, collisions, IDLE_INPUT);
            assertTrue(result.eligible());
            assertTrue(result.suspicious());
            assertFalse(result.flagged());
        }

        var fourth = window.accept(previous, 0, 0,
                0, 64, 0, environment, collisions, IDLE_INPUT);
        assertTrue(fourth.flagged());
        assertTrue(fourth.withPushOffset() > fourth.withoutPushOffset());
    }

    @Test void vanillaEntityPushMatchesPushModelInsteadOfNoPushModel() {
        var window = new EntityPushSuppressionWindow();
        var environment = ground(1_000);
        var collisions = push(0.05, 0, false);
        var previous = new MotionPredictor.Motion(0, 0, 0);

        // Neutral-input ground motion carries the 0.05 push through
        // 0.6 * 0.91 horizontal friction.
        double expectedX = 0.05 * (double) (0.6f * 0.91f);
        var result = window.accept(previous, expectedX, 0,
                0, 64, 0, environment, collisions, IDLE_INPUT);

        assertTrue(result.eligible());
        assertFalse(result.suspicious());
        assertFalse(result.flagged());
    }

    @Test void movingInputAndHardCollisionAreNotPushSuppressionEvidence() {
        var window = new EntityPushSuppressionWindow();
        var environment = ground(1_000);
        var previous = new MotionPredictor.Motion(0, 0, 0);
        var forward = new JavaInputCapture.Window(new MotionPredictor.Input(
                true, false, false, false, false, false, false), null);

        assertFalse(window.accept(previous, 0, 0,
                0, 64, 0, environment, push(0.05, 0, false), forward).eligible());
        assertFalse(window.accept(previous, 0, 0,
                0, 64, 0, environment, push(0.05, 0, true), IDLE_INPUT).eligible());
    }

    @Test void tinyContactNoiseDoesNotBecomeNoPushEvidence() {
        var window = new EntityPushSuppressionWindow();
        var result = window.accept(new MotionPredictor.Motion(0, 0, 0), 0, 0,
                0, 64, 0, ground(1_000), push(0.01, 0, false), IDLE_INPUT);

        assertFalse(result.eligible());
        assertFalse(result.flagged());
    }

    private static MotionEnvironment.Snapshot ground(long at) {
        return new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, 0, (int) (at / 50), at);
    }

    private static MotionCollisionSnapshot push(double x, double z, boolean hardCollision) {
        return new MotionCollisionSnapshot(
                -5, 60, -5, 5, 70, 5,
                0.6, 1.8, 0.6, 0.6,
                List.of(), true, true, 1, hardCollision,
                MotionCollisionSnapshot.StepProfile.V1_21_PLUS, 1_000,
                List.of(new MotionCollisionSnapshot.EntityPush(x, z)));
    }
}
