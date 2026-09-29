package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.packet.JavaInputCapture;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterMotionPredictorTest {
    private static final WaterMotionEnvironment.Snapshot STILL_WATER =
            new WaterMotionEnvironment.Snapshot(0, 64, 0, 0,
                    0.1, 0, 0.08, false, false,
                    false, false, 0.3f, 1.0f, 0, 0, 0, 1, 1_000);
    private static final MotionCollisionSnapshot CLEAR_VOLUME =
            new MotionCollisionSnapshot(-5, 60, -5, 5, 70, 5,
                    0.6, 1.8, 0.6, 0.6, List.of(), true, 1_000);

    @Test void matchesPaperStillWaterAccelerationDragAndFallingGravity() {
        MotionPredictor.Input forward = new MotionPredictor.Input(
                true, false, false, false, false, false, false);
        var first = WaterMotionPredictor.step(new MotionPredictor.Motion(0, 0, 0),
                0, STILL_WATER, forward, false);
        var second = WaterMotionPredictor.step(first, 0, STILL_WATER, forward, false);

        assertEquals(0.0196, first.dz(), 1.0e-7);
        assertEquals(-0.005, first.dy(), 1.0e-7);
        assertEquals(0.03528, second.dz(), 1.0e-7);
        assertEquals(-0.009, second.dy(), 1.0e-7);
    }

    @Test void sprintChangesWaterDragAndSuppressesFluidGravityAdjustment() {
        MotionPredictor.Input idle = new MotionPredictor.Input(
                false, false, false, false, false, false, true);
        var step = WaterMotionPredictor.step(new MotionPredictor.Motion(0.1, -0.1, 0),
                0, STILL_WATER, idle, true);

        assertEquals(0.09, step.dx(), 1.0e-7);
        assertEquals(-0.08, step.dy(), 1.0e-7);
    }

    @Test void jumpImpulseIsIncludedInWaterMovementCandidate() {
        MotionPredictor.Input jump = new MotionPredictor.Input(
                false, false, false, false, true, false, false);
        var step = WaterMotionPredictor.step(new MotionPredictor.Motion(0, 0, 0),
                0, STILL_WATER, jump, false);

        assertEquals(0.035, step.dy(), 1.0e-7);
    }

    @Test void noPushSinkingZeroesVerticalMotionAndLeavesPredictionResidual() {
        MotionPredictor.Input idle = new MotionPredictor.Input(
                false, false, false, false, false, false, false);
        var previous = new MotionPredictor.Motion(0, -0.10, 0);
        var vanilla = WaterMotionPredictor.step(previous, 0, STILL_WATER, idle, false);

        // LiquidBounce NoPush.SINKING writes player.deltaMovement.y = 0 while
        // falling in liquid. The server's still-water recurrence remains
        // negative, so the forged zero-Y step must not fit the predictor.
        var prediction = WaterMotionPredictor.predict(previous,
                new MotionPredictor.Motion(0, 0, 0), 0, STILL_WATER,
                new JavaInputCapture.Window(idle, null), 0, 64, 0, CLEAR_VOLUME);

        assertTrue(vanilla.dy() < -0.05);
        assertNotNull(prediction);
        assertTrue(prediction.offset() > 0.05);
    }

    @Test void waterCurrentIsAddedToTheNextMovementAndKeepsMojangMinimumPush() {
        var current = new WaterMotionEnvironment.Snapshot(0, 64, 0, 0,
                0.1, 0, 0.08, false, false,
                false, false, 0.3f, 1.0f, 0.014, 0, 0, 1, 1_000);
        MotionPredictor.Input idle = new MotionPredictor.Input(
                false, false, false, false, false, false, false);
        var pushed = WaterMotionPredictor.step(new MotionPredictor.Motion(0, 0, 0),
                0, current, idle, false);
        assertEquals(0.014, pushed.dx(), 1.0e-7);

        var weakCurrent = new WaterMotionEnvironment.Snapshot(0, 64, 0, 0,
                0.1, 0, 0.08, false, false,
                false, false, 0.3f, 1.0f, 0.002, 0, 0, 1, 1_000);
        var minimumPush = WaterMotionPredictor.step(new MotionPredictor.Motion(0, 0, 0),
                0, weakCurrent, idle, false);
        assertEquals(0.0045, minimumPush.dx(), 1.0e-7);
    }

    @Test void exactStillWaterStepMatchesAndSpeedBurstHasAnOffset() {
        MotionPredictor.Input forward = new MotionPredictor.Input(
                true, false, false, false, false, false, false);
        var previous = new MotionPredictor.Motion(0, 0, 0.0196);
        var legal = WaterMotionPredictor.step(previous, 0, STILL_WATER, forward, false);
        var matched = WaterMotionPredictor.predict(previous, legal, 0, STILL_WATER,
                new JavaInputCapture.Window(forward, null), 0, 64, 0.0196, CLEAR_VOLUME);
        var speedBurst = WaterMotionPredictor.predict(previous,
                new MotionPredictor.Motion(0, 0, 0.2), 0, STILL_WATER,
                new JavaInputCapture.Window(forward, null), 0, 64, 0.0196, CLEAR_VOLUME);

        assertNotNull(matched);
        assertEquals(0, matched.offset(), 1.0e-7);
        assertNotNull(speedBurst);
        assertTrue(speedBurst.offset() > 0.1);
    }

    @Test void wetContactWindowProtectsGroundAndAirChecksNearWaterAndExpiresQuickly() {
        WaterMotionEnvironment environment = new WaterMotionEnvironment();
        UUID uuid = UUID.randomUUID();
        environment.recordContact(uuid, 10, 64, 10, true, 1_000);

        assertTrue(environment.wetNear(uuid, 10.2, 64, 10, 1_150));
        assertFalse(environment.wetNear(uuid, 12, 64, 10, 1_150));
        assertFalse(environment.wetNear(uuid, 10, 64, 10, 1_201));

        environment.recordContact(uuid, 10, 64, 10, false, 1_210);
        assertFalse(environment.wetNear(uuid, 10, 64, 10, 1_211));
    }

    @Test void detectsSuppressedCurrentWhilePlayerSteersAcrossTheFlow() {
        var forward = new MotionPredictor.Input(true, false, false, false,
                false, false, false);
        var input = new JavaInputCapture.Window(forward, null);
        var dry = new WaterMotionEnvironment.Snapshot(0, 64, 0, 0,
                0.1, 0, 0.08, false, false, false, false,
                0.3f, 1.0f, 0, 0, 0, 1, 1_000);
        for (boolean submerged : new boolean[] {true, false}) {
            var sequence = new WaterMotionSequence();
            double x = 0, y = 64, z = 0;
            var velocity = new MotionPredictor.Motion(0, 0, 0);
            int suppressed = 0;
            for (int frame = 0; frame < 15; frame++) {
                long now = 1_000 + frame * 50L;
                var wet = new WaterMotionEnvironment.Snapshot(x, y, z, 0,
                        0.1, 0, 0.08, false, false, false, false,
                        0.3f, 1.0f, 0.014, 0, 0, frame + 1, now, submerged);
                var collisions = new MotionCollisionSnapshot(-5, 60, -5, 5, 70, 5,
                        0.6, 1.8, 0.6, 0.6, List.of(), true, now);
                var sample = sequence.accept(true, x, y, z, 0, wet, collisions,
                        input, null, now);
                if (sample.suppressedCurrent()) suppressed++;
                velocity = WaterMotionPredictor.step(velocity, 0, dry, forward, false);
                x += velocity.dx(); y += velocity.dy(); z += velocity.dz();
            }
            assertTrue(suppressed >= 10,
                    "active steering must not hide cancelled fluid pushing, submerged=" + submerged);
        }
    }
}
