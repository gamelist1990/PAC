package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.packet.JavaInputCapture;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPushSuppressionWindowTest {
    @Test void queuedPacketsCannotMultiplyOnePushObservation() {
        var window = new EntityPushSuppressionWindow();
        var collisions = push(0.05, 0, false);
        for (int packet = 0; packet < 12; packet++) {
            assertFalse(window.accept(new MotionPredictor.Motion(0, 0, 0), 0, 0,
                    0, 64, 0, ground(1000), collisions, IDLE_INPUT).flagged());
        }
    }

    @Test void boatCollisionDiscardsExistingPushEvidence() {
        var window = new EntityPushSuppressionWindow();
        for (int sample = 0; sample < 3; sample++) {
            window.accept(new MotionPredictor.Motion(0, 0, 0), 0, 0,
                    0, 64, 0, ground(1000 + sample * 50),
                    push(0.05, 0, false, 1000 + sample * 50), IDLE_INPUT);
        }
        for (int sample = 3; sample < 10; sample++) {
            assertFalse(window.accept(new MotionPredictor.Motion(0, 0, 0), 0, 0,
                    0, 64, 0, ground(1000 + sample * 50),
                    push(0.05, 0, true, 1000 + sample * 50), IDLE_INPUT).eligible());
        }
        assertFalse(window.accept(new MotionPredictor.Motion(0, 0, 0), 0, 0,
                0, 64, 0, ground(1500), push(0.05, 0, false, 1500), IDLE_INPUT).flagged());
    }

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
                    0, 64, 0, ground(1000 + sample * 50), push(0.05, 0, false, 1000 + sample * 50), IDLE_INPUT);
            assertTrue(result.eligible());
            assertTrue(result.suspicious());
            assertFalse(result.flagged());
        }

        var fourth = window.accept(previous, 0, 0,
                0, 64, 0, ground(1200), push(0.05, 0, false, 1200), IDLE_INPUT);
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

    @Test void stableMovingInputCanStillProveNoPush() {
        var window = new EntityPushSuppressionWindow();
        var environment = ground(1_000);
        var forwardInput = new MotionPredictor.Input(
                true, false, false, false, false, false, false);
        var stableForward = new JavaInputCapture.Window(forwardInput, forwardInput);
        var previous = new MotionPredictor.Motion(0, 0, 0);
        boolean detected = false;

        for (int sample = 0; sample < 5 && !detected; sample++) {
            var noPush = MotionPredictor.predictGroundInputClient(previous,
                    new MotionPredictor.Motion(0, 0, 0), environment.yaw(),
                    environment.movementSpeed(), environment.groundFriction(),
                    environment.horizontalDrag(), 1.0f,
                    environment.itemUseMultiplier(), forwardInput).closest();
            var result = window.accept(previous, noPush.dx(), noPush.dz(),
                    0, 64, sample * 0.1, environment,
                    push(0.05, 0, false, 1000 + sample * 50), stableForward);
            detected = result.flagged();
            previous = noPush;
        }

        assertTrue(detected);
    }

    @Test void inputTransitionAndHardCollisionAreNotPushSuppressionEvidence() {
        var window = new EntityPushSuppressionWindow();
        var environment = ground(1_000);
        var previous = new MotionPredictor.Motion(0, 0, 0);
        var forward = new MotionPredictor.Input(
                true, false, false, false, false, false, false);
        var transitioning = new JavaInputCapture.Window(forward, IDLE);

        assertFalse(window.accept(previous, 0, 0,
                0, 64, 0, environment, push(0.05, 0, false), transitioning).eligible());
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
        return new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, 0, 64, 0, (int) (at / 50), at);
    }

    private static MotionCollisionSnapshot push(double x, double z, boolean hardCollision) {
        return push(x, z, hardCollision, 1000);
    }

    private static MotionCollisionSnapshot push(double x, double z, boolean hardCollision, long at) {
        return new MotionCollisionSnapshot(
                -5, 60, -5, 5, 70, 5,
                0.6, 1.8, 0.6, 0.6,
                List.of(), true, true, 1, hardCollision,
                MotionCollisionSnapshot.StepProfile.V1_21_PLUS, at,
                List.of(new MotionCollisionSnapshot.EntityPush(x, z)));
    }
}
