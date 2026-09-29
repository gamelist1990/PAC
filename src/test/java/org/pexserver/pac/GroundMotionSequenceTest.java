package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.GroundMotionSequence;
import org.pexserver.pac.movement.MultiStepMotionPredictor;
import org.pexserver.pac.movement.MotionEnvironment;
import org.pexserver.pac.movement.MotionCollisionSnapshot;
import org.pexserver.pac.movement.MotionPredictor;
import org.pexserver.pac.packet.ExternalMotionTracker;
import org.pexserver.pac.packet.JavaInputCapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundMotionSequenceTest {
    @Test void iceHeadBonkSprintImpulseIsIncludedInGroundSpeedPrediction() {
        var sequence = new GroundMotionSequence();
        var ceiling = new MotionCollisionSnapshot(-2, 62, -2, 6, 68, 2,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(new MotionCollisionSnapshot.Box(-2, 65.8, -1, 6, 66.8, 1)),
                true, 1_050);
        sequence.rebase(0, 64, 0, 0.2, 0, 0, ice(1, 0, 1_000), 1_000);
        var first = sequence.accept(true, false, 0.4, 64, 0, 0,
                ice(2, 0.4, 1_050), 1_050, null, null, ceiling);
        assertTrue(first.evaluated());
        assertEquals(0, first.speedExcess(), 0.01);
        assertEquals(0, first.offset(), 0.01);
        var second = sequence.accept(true, false, 0.95, 64, 0, 0,
                ice(3, 0.95, 1_100), 1_100, null, null,
                new MotionCollisionSnapshot(-2, 62, -2, 6, 68, 2,
                        0.6, 1.8, 0.6, 0.6,
                        java.util.List.of(new MotionCollisionSnapshot.Box(-2, 65.8, -1, 6, 66.8, 1)),
                        true, 1_100));
        assertEquals(0, second.speedExcess(), 0.01);
    }

    @Test void iceAccelerationWithoutHeadCollisionStillHasSpeedExcess() {
        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0, 0.2, 0, 0, ice(1, 0, 1_000), 1_000);
        var empty = new MotionCollisionSnapshot(-2, 62, -2, 6, 68, 2,
                0.6, 1.8, 0.6, 0.6, java.util.List.of(), true, 1_050);
        var accelerated = sequence.accept(true, false, 0.4, 64, 0, 0,
                ice(2, 0.4, 1_050), 1_050, null, null, empty);
        assertTrue(accelerated.speedExcess() > 0.1);
    }

    private MotionEnvironment.Snapshot ice(int tick, double x, long at) {
        return new MotionEnvironment.Snapshot(true, false, true, false, false,
                0, 0.13, x, 64, 0, tick, at, false, false,
                0.98f, 0.08, 0.91f, 0.98f, 0.42f);
    }

    private MotionEnvironment.Snapshot ground(double z, int tick, long at) {
        return new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, 0, 64, z, tick, at);
    }

    private MotionEnvironment.Snapshot air(double y, int tick, long at) {
        return new MotionEnvironment.Snapshot(false, true, false, false,
                0, 0.1, 0, y, 0, tick, at);
    }

    private MotionEnvironment.Snapshot groundAt(double x, double y, double z, int tick, long at) {
        return new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, x, y, z, tick, at);
    }

    @Test void rollbackRebasesThePredictionOriginToTheSetbackAnchor() {
        GroundMotionSequence sequence = new GroundMotionSequence();
        sequence.accept(true, true, 20, 70, 30, 0,
                groundAt(20, 70, 30, 1, 1_000), 1_000);

        sequence.rebase(2, 64, 3, 0.05, 0, 0.1,
                groundAt(2, 64, 3, 2, 1_050), 1_050);

        assertEquals(new GroundMotionSequence.Position(2, 64, 3), sequence.lastPosition());
    }

    @Test void duplicateMovementBranchesDoNotConsumeTheMultiStepBeam() {
        var environment = groundAt(0, 64, 0, 1, 1_000);
        var forward = new MotionPredictor.Input(true, false, false, false,
                false, false, false);
        var single = new MultiStepMotionPredictor.Frame(0,
                java.util.List.of(forward), environment, false);
        var duplicates = new MultiStepMotionPredictor.Frame(0,
                java.util.Collections.nCopies(2_049, forward), environment, false);
        var initial = new MotionPredictor.Motion(0, 0, 0);

        var expected = MultiStepMotionPredictor.ground(initial, 0.25, -0.3,
                java.util.List.of(single, single, single), 0, 64, 0);
        var actual = MultiStepMotionPredictor.ground(initial, 0.25, -0.3,
                java.util.List.of(duplicates, duplicates, duplicates), 0, 64, 0);

        assertEquals(expected.offset(), actual.offset(), 0);
        assertEquals(expected.finalVelocity().dx(), actual.finalVelocity().dx(), 0);
        assertEquals(expected.finalVelocity().dy(), actual.finalVelocity().dy(), 0);
        assertEquals(expected.finalVelocity().dz(), actual.finalVelocity().dz(), 0);
    }

    @Test void groundedReplayScoresVerticalDisplacementWhenCollisionGeometryIsComplete() {
        var environment = groundAt(0, 64, 0, 1, 1_000);
        var collision = new MotionCollisionSnapshot(-2, 62, -2, 2, 68, 2,
                0.6, 1.8, 0.6, 0.6, java.util.List.of(), true, 1_000);
        var noInput = new MotionPredictor.Input(false, false, false, false,
                false, false, false);
        var frame = new MultiStepMotionPredictor.Frame(0,
                java.util.List.of(noInput), environment, false, collision);
        var initial = new MotionPredictor.Motion(0, 0, 0);

        var legacyHorizontalOnly = MultiStepMotionPredictor.ground(initial, 0, 0,
                java.util.List.of(frame), 0, 64, 0);
        var fullySimulated = MultiStepMotionPredictor.ground3d(initial, 0, 0.25, 0,
                java.util.List.of(frame), 0, 64, 0);

        assertEquals(0, legacyHorizontalOnly.offset(), 0);
        assertEquals(0.25, fullySimulated.offset(), 1.0e-12);
    }

    @Test void groundedReplayMatchesAabbResolvedHalfStepInAllThreeAxes() {
        var environment = new MotionEnvironment.Snapshot(true, false, false, false,
                0, 1.0, 0, 64, 0, 1, 1_000);
        var noInput = new MotionPredictor.Input(false, false, false, false,
                false, false, false);
        var collision = new MotionCollisionSnapshot(-2, 62, -2, 3, 68, 2,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(
                        new MotionCollisionSnapshot.Box(-2, 63, -2, 3, 64, 2),
                        new MotionCollisionSnapshot.Box(0.5, 64, -1, 1.5, 64.5, 1)),
                true, 1_000);
        var frame = new MultiStepMotionPredictor.Frame(0,
                java.util.List.of(noInput), environment, false, collision);
        var initial = new MotionPredictor.Motion(0.6, 0, 0);
        var free = MotionPredictor.predictGroundInputClient(initial,
                new MotionPredictor.Motion(0, 0, 0), 0, environment.movementSpeed(),
                environment.groundFriction(), environment.horizontalDrag(), 1,
                environment.itemUseMultiplier(), noInput).closest();
        var stepped = collision.resolve(0, 64, 0, free.dx(), 0, free.dz(), true).stream()
                .filter(move -> move.y() > 0).findFirst().orElseThrow();

        var result = MultiStepMotionPredictor.ground3d(initial,
                stepped.x(), stepped.y(), stepped.z(), java.util.List.of(frame), 0, 64, 0);

        assertEquals(0, result.offset(), 1.0e-12);
    }

    @Test void legalPacketsQueuedInOneServerTickStillMatchSimulation() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1000), 1000);
        double firstStep = (double) 0.1f * (double) 0.98f;
        sequence.accept(true, false, 0, 64, firstStep, 0, ground(firstStep, 1, 1001), 1001);
        var third = sequence.accept(true, false, 0, 64, firstStep + firstStep + firstStep * (0.6f * 0.91f),
                0, ground(0.25, 1, 1002), 1002);
        assertTrue(third.evaluated());
        assertEquals(0, third.offset(), 1e-10);
    }

    @Test void repeatedFastMovementExceedsGroundCandidateEnvelope() {
        var sequence = new GroundMotionSequence();
        assertFalse(sequence.accept(true, true, 0, 64, 0, 0,
                ground(0, 1, 1000), 1000).evaluated());
        assertFalse(sequence.accept(true, false, 0, 64, 0.45, 0,
                ground(0.45, 1, 1050), 1050).evaluated());
        var third = sequence.accept(true, false, 0, 64, 0.90, 0,
                ground(0.90, 1, 1100), 1100);
        assertTrue(third.evaluated());
        assertTrue(third.offset() > 0.08);
    }

    @Test void fiftyPercentSpeedIncreaseExceedsConfiguredDefault() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1000), 1000);
        sequence.accept(true, false, 0, 64, 0.33, 0, ground(0.33, 1, 1050), 1050);
        var third = sequence.accept(true, false, 0, 64, 0.66, 0,
                ground(0.66, 1, 1100), 1100);
        assertTrue(third.evaluated());
        assertTrue(third.offset() > 0.04);
    }

    @Test void pluginWalkSpeedAttributeIsPredictedWithoutFixedOneBlockLimit() {
        var sequence = new GroundMotionSequence();
        double speed = 1.0;
        double step = speed * 0.98f;
        double drag = 0.6f * 0.91f;
        var first = new MotionEnvironment.Snapshot(true, false, false, false,
                0, speed, 0, 64, 0, 1, 1000);
        var second = new MotionEnvironment.Snapshot(true, false, false, false,
                0, speed, 0, 64, step, 2, 1050);
        double thirdZ = step + step * drag + step;
        var third = new MotionEnvironment.Snapshot(true, false, false, false,
                0, speed, 0, 64, thirdZ, 3, 1100);
        sequence.accept(true, true, 0, 64, 0, 0, first, 1000);
        assertFalse(sequence.accept(true, false, 0, 64, step, 0, second, 1050).abrupt());
        var result = sequence.accept(true, false, 0, 64, thirdZ, 0, third, 1100);
        assertTrue(result.evaluated());
        assertFalse(result.abrupt());
        assertEquals(0, result.offset(), 1e-10);
    }

    @Test void liquidBounceHighJumpDefaultMotionIsAnImpossibleTakeoff() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1000), 1000);

        var sample = sequence.accept(true, false, 0, 64.8, 0.25, 0,
                air(64.8, 2, 1050), 1050);

        assertTrue(sample.impossibleTakeoff(),
                "LiquidBounce HighJump's default 0.8Y launch must exceed the server jump-strength envelope");
    }

    @Test void shortUpwardStepWithoutCollisionIsAnImpossibleTakeoff() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1000), 1000);
        var sample = sequence.accept(true, false, 0, 64.1, 0.36, 0,
                ground(0, 2, 1050), 1050);
        assertTrue(sample.impossibleTakeoff());
    }

    @Test void jumpIntoLowCeilingMatchesTheExactClippedAabbMovement() {
        var sequence = new GroundMotionSequence();
        var firstEnvironment = ground(0, 1, 1000);
        var firstCollision = new MotionCollisionSnapshot(-2, 62, -2, 2, 68, 2,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(new MotionCollisionSnapshot.Box(-0.5, 66, -0.5,
                        0.5, 67, 0.5)), true, 1000);
        sequence.accept(true, true, 0, 64, 0, 0, firstEnvironment, 1000,
                null, null, firstCollision);

        var secondEnvironment = ground(0, 2, 1050);
        var secondCollision = new MotionCollisionSnapshot(-2, 62, -2, 2, 68, 2,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(new MotionCollisionSnapshot.Box(-0.5, 66, -0.5,
                        0.5, 67, 0.5)), true, 1050);
        var clippedJump = sequence.accept(true, false, 0, 64.2, 0, 0,
                secondEnvironment, 1050, null, null, secondCollision);
        assertFalse(clippedJump.impossibleTakeoff());
    }

    @Test void headBonkStillMatchesWhenNearbyEntityMakesHorizontalPredictionUncertain() {
        var sequence = new GroundMotionSequence();
        var firstEnvironment = ground(0, 1, 1000);
        var ceiling = new MotionCollisionSnapshot(-2, 62, -2, 2, 68, 2,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(new MotionCollisionSnapshot.Box(-0.5, 66, -0.5,
                        0.5, 67, 0.5)), true, 1000);
        sequence.accept(true, true, 0, 64, 0, 0, firstEnvironment, 1000,
                null, null, ceiling);

        // The observed sprint-jump horizontal step is not predicted exactly
        // because an entity can push the player, while the block AABB still
        // clips the vanilla jump from 0.42 to 0.2 at the ceiling.
        var uncertainCollision = new MotionCollisionSnapshot(-2, 62, -2, 2, 68, 2,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(new MotionCollisionSnapshot.Box(-0.5, 66, -0.5,
                        0.5, 67, 0.5)), false, true, true, 1050);
        var clippedJump = sequence.accept(true, false, 0.48, 64.2, 0, 0,
                ground(0.48, 2, 1050), 1050, null, null, uncertainCollision);

        assertFalse(clippedJump.impossibleTakeoff(),
                "complete block geometry must validate the clipped vertical jump despite entity push uncertainty");
    }

    @Test void quarterAndHalfBlockStepUpsAreNotClassifiedAsImpossibleTakeoffs() {
        for (double stepHeight : new double[] {0.25, 0.5}) {
            var sequence = new GroundMotionSequence();
            var initialCollision = stepCollision(stepHeight, 1000);
            sequence.accept(true, true, -0.3, 0, 0, 0,
                    groundAtSpeed(-0.3, 0, 0, 1, 1000, 0.3), 1000,
                    null, null, initialCollision);

            var collision = stepCollision(stepHeight, 1050);
            boolean simulatedStep = collision.resolve(-0.3, 0, 0, 0.18, 0, 0, true).stream()
                    .anyMatch(move -> Math.abs(move.x() - 0.18) < 1.0e-6
                            && Math.abs(move.y() - stepHeight) < 1.0e-6);
            assertTrue(simulatedStep, "test geometry must expose the vanilla " + stepHeight + " AABB step");

            var stepped = sequence.accept(true, false, -0.12, stepHeight, 0, 0,
                    groundAtSpeed(-0.12, stepHeight, 0, 2, 1050, 0.3), 1050,
                    null, null, collision);

            assertFalse(stepped.impossibleTakeoff(),
                    "a vanilla AABB step of " + stepHeight + " blocks is not an impossible jump");
        }
    }

    @Test void lowHopAfterLandingArmsFromFirstSupportedFrame() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64.12, 0, 0, air(64.12, 1, 1000), 1000);
        sequence.accept(true, false, 0, 64, 0, 0, ground(0, 2, 1050), 1050);
        var hop = sequence.accept(true, false, 0, 64.1, 0.36, 0,
                air(64.1, 3, 1100), 1100);
        assertTrue(hop.impossibleTakeoff());
    }

    @Test void upwardLandingOnHigherBlockDoesNotArmAnotherTakeoffBesideWall() {
        var sequence = new GroundMotionSequence();
        var wallAndLedge = new MotionCollisionSnapshot(-2, 62, -2, 3, 69, 3,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(
                        new MotionCollisionSnapshot.Box(-2, 63, -2, 3, 64, 3),
                        new MotionCollisionSnapshot.Box(0.3, 64, -1, 1.3, 65, 1),
                        new MotionCollisionSnapshot.Box(-1, 64, 0.5, 1, 67, 1.5)),
                true, 1_150);
        sequence.accept(true, true, 0, 64, 0, 0,
                groundAt(0, 64, 0, 1, 1_000), 1_000);
        sequence.accept(true, false, 0, 64.42, 0, 0,
                air(64.42, 2, 1_050), 1_050);
        sequence.accept(true, false, 0.18, 64.7532, 0, 0,
                air(64.7532, 3, 1_100), 1_100);
        sequence.accept(true, false, 0.4, 65, 0, 0,
                groundAt(0.4, 65, 0, 4, 1_150), 1_150,
                null, null, wallAndLedge);

        var settling = sequence.accept(true, false, 0.42, 65.063, 0, 0,
                groundAt(0.42, 65.063, 0, 5, 1_200), 1_200,
                null, null, wallAndLedge);
        assertFalse(settling.impossibleTakeoff(),
                "an upward landing must not arm a second jump at the ledge");
    }

    @Test void specialBounceSurfaceDoesNotBecomeImpossibleTakeoffEvidence() {
        var sequence = new GroundMotionSequence();
        var surface = ground(0, 1, 1000).withSpecialVerticalSurface(true);
        sequence.accept(true, true, 0, 64, 0, 0, surface, 1000);

        var bounced = sequence.accept(true, false, 0, 64.8, 0, 0,
                ground(0, 2, 1050).withSpecialVerticalSurface(true), 1050);

        assertFalse(bounced.impossibleTakeoff(),
                "slime/honey vertical response must not be judged by the ordinary jump envelope");
    }

    @Test void normalJumpDoesNotTriggerSpeedHackTakeoff() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1000), 1000);
        var sample = sequence.accept(true, false, 0, 64.42, 0.29, 0,
                ground(0, 2, 1050), 1050);
        assertFalse(sample.impossibleTakeoff());
    }

    @Test void secondQueuedNormalJumpFrameIsNotNewTakeoff() {
        var sequence = new GroundMotionSequence();
        var staleGround = ground(0, 1, 1000);
        sequence.accept(true, true, 0, 64, 0, 0, staleGround, 1000);
        assertFalse(sequence.accept(true, false, 0, 64.42, 0.29, 0,
                staleGround, 1001).impossibleTakeoff());
        assertFalse(sequence.accept(true, false, 0, 64.7532, 0.53, 0,
                staleGround, 1002).impossibleTakeoff());
    }

    @Test void tooHighFirstJumpCannotResetGroundPrediction() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1000), 1000);
        assertTrue(sequence.accept(true, false, 0, 64.70, 0.35, 0,
                ground(0, 1, 1000), 1001).impossibleTakeoff());
    }

    @Test void takeoffStillCheckedWhenServerSnapshotAlreadyShowsAir() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1000), 1000);
        assertTrue(sequence.accept(true, false, 0, 64.7, 0.3, 0,
                air(64.7, 2, 1050), 1050).impossibleTakeoff());
    }

    @Test void normalJumpWithAirSnapshotDisarmsBeforeSecondFrame() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1000), 1000);
        assertFalse(sequence.accept(true, false, 0, 64.42, 0.28, 0,
                air(64.42, 2, 1050), 1050).impossibleTakeoff());
        assertFalse(sequence.accept(true, false, 0, 64.7532, 0.5, 0,
                air(64.7532, 3, 1100), 1100).impossibleTakeoff());
    }

    @Test void configuredJumpStrengthChangesTakeoffEnvelope() {
        var sequence = new GroundMotionSequence();
        var snapshot = new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, 0, 1, 1000, false, false,
                0.6f, 0.08, 0.91f, 0.98f, 0.7f);
        sequence.accept(true, true, 0, 64, 0, 0, snapshot, 1000);
        assertFalse(sequence.accept(true, false, 0, 64.7, 0.4, 0,
                snapshot, 1001).impossibleTakeoff());
    }

    @Test void pluginJumpStrengthAboveOneBlockIsNotAnAbruptTeleport() {
        var sequence = new GroundMotionSequence();
        var ground = new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, 0, 1, 1000, false, false,
                0.6f, 0.08, 0.91f, 0.98f, 1.5f);
        sequence.accept(true, true, 0, 64, 0, 0, ground, 1000);
        var first = sequence.accept(true, false, 0, 65.5, 0, 0,
                ground, 1050);
        assertFalse(first.abrupt());
        assertFalse(first.impossibleTakeoff());
        double nextY = 65.5 + (1.5 - 0.08) * 0.98f;
        var second = sequence.accept(true, false, 0, nextY, 0, 0,
                ground, 1100);
        assertFalse(second.abrupt());
    }

    @Test void iceFramesAreEvaluatedWithIceFriction() {
        var sequence = new GroundMotionSequence();
        float ice = 0.98f;
        double acceleration = 0.1 * (0.21600002f / (ice * ice * ice)) * 0.98f;
        var first = new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, 0, 1, 1000, false, false, ice);
        var second = new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, acceleration, 2, 1050, false, false, ice);
        double nextZ = acceleration + acceleration * (ice * 0.91f) + acceleration;
        var third = new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, nextZ, 3, 1100, false, false, ice);
        sequence.accept(true, true, 0, 64, 0, 0, first, 1000);
        sequence.accept(true, false, 0, 64, acceleration, 0, second, 1050);
        var result = sequence.accept(true, false, 0, 64, nextZ, 0, third, 1100);
        assertTrue(result.evaluated());
        assertEquals(0, result.offset(), 1e-12);
    }

    @Test void pluginVelocityIsAcceptedThenVanillaPredictionResumesFromItsResult() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1_000), 1_000);

        double impulseVelocity = 0.8;
        var pluginVelocity = new ExternalMotionTracker.Impulse(
                1, impulseVelocity, 0, 0, 1_049, false);
        double firstClientStep = impulseVelocity * (double) (0.6f * 0.91f);
        var afterPluginVelocity = sequence.accept(true, false, firstClientStep, 64, 0, 0,
                ground(0, 2, 1_050), 1_050, null, pluginVelocity);
        assertFalse(afterPluginVelocity.evaluated());

        double nextClientStep = firstClientStep * (double) (0.6f * 0.91f);
        var resumedPrediction = sequence.accept(true, false, firstClientStep + nextClientStep,
                64, 0, 0, ground(firstClientStep + nextClientStep, 3, 1_100), 1_100);
        assertTrue(resumedPrediction.evaluated());
        assertEquals(0, resumedPrediction.offset(), 1.0e-10);
    }

    @Test void combatVelocityWithNoClientResponseIsFlagged() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1_000), 1_000,
                null, null, fullFloor(1_000));

        var impulse = new ExternalMotionTracker.Impulse(1, 0.8, 0, 0, 1_049, false, true);
        var noKnockback = sequence.accept(true, false, 0, 64, 0, 0,
                ground(0, 2, 1_050), 1_050, null, impulse, fullFloor(1_050));

        assertTrue(noKnockback.externalImpulseMismatch(), "a cancelled combat impulse must not become the new baseline");
    }

    @Test void vanillaCombatKnockbackResponseMatchesItsServerImpulse() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1_000), 1_000,
                null, null, fullFloor(1_000));

        double impulseX = 0.8;
        double firstClientStep = impulseX * (double) (0.6f * 0.91f);
        var response = sequence.accept(true, false, firstClientStep, 64.42, 0, 0,
                ground(0, 2, 1_050), 1_050, null,
                new ExternalMotionTracker.Impulse(1, impulseX, 0.42, 0, 1_049, false, true),
                fullFloor(1_050));

        assertFalse(response.externalImpulseMismatch());
    }

    @Test void observedCombatKnockbackVelocityIsNotFlaggedAsSuppressed() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1_000), 1_000,
                null, null, fullFloor(1_000));

        double impulseX = -0.076, impulseY = 0.275, impulseZ = 0.205;
        double observedHorizontal = 0.223;
        double scale = observedHorizontal / Math.hypot(impulseX, impulseZ);
        double dx = impulseX * scale, dz = impulseZ * scale;
        var response = sequence.accept(true, false, dx, 64 + impulseY, dz, 0,
                ground(0, 2, 1_050), 1_050, null,
                new ExternalMotionTracker.Impulse(1, impulseX, impulseY, impulseZ,
                        1_049, false, true), fullFloor(1_050));

        assertFalse(response.externalImpulseMismatch(),
                "a full observed velocity response must not be labeled AntiKB due to a first-frame residual");
    }

    @Test void groundedJumpResetUsesAirborneKnockbackPhysicsForTheImpactFrame() {
        var sequence = new GroundMotionSequence();
        var floor = fullFloor(1_000);
        var environment = ground(0, 1, 1_000);
        sequence.accept(true, true, 0, 64, 0, 0, environment, 1_000,
                null, null, floor);

        var input = new MotionPredictor.Input(true, false, false, false,
                true, false, false);
        var inputs = new JavaInputCapture.Window(input, null);
        var impactEnvironment = ground(0, 2, 1_050);
        var impactFrame = MultiStepMotionPredictor.frame(0, inputs, impactEnvironment,
                fullFloor(1_050));
        var impulseVelocity = new MotionPredictor.Motion(0.044, 0.275, 0.214);
        var expected = MotionPredictor.predictAirInputClient(impulseVelocity,
                new MotionPredictor.Motion(0, 0, 0), 0, false, 0.91f,
                1, 1, input).closest();

        var response = sequence.accept(true, false,
                expected.dx(), 64.42, expected.dz(), 0,
                impactEnvironment, 1_050, inputs,
                new ExternalMotionTracker.Impulse(1, impulseVelocity.dx(), impulseVelocity.dy(),
                        impulseVelocity.dz(), 1_049, false, true),
                fullFloor(1_050));

        assertFalse(response.externalImpulseMismatch(),
                "a jump reset on the impact tick uses air drag and still preserves the knockback response: "
                        + response);
    }

    @Test void smallGroundDeltaImmediatelyAfterCombatImpulseIsNotMisclassifiedAsTakeoff() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, groundAt(0, 64, 0, 1, 1_000), 1_000,
                null, null, fullFloor(1_000));
        sequence.accept(true, false, 0.2, 64.42, 0, 0,
                groundAt(0.2, 64.42, 0, 2, 1_050), 1_050, null,
                new ExternalMotionTracker.Impulse(1, 0.2, 0.275, 0, 1_049, false, true),
                fullFloor(1_050));

        var impactSettling = sequence.accept(true, false, 0.425, 64.483, 0, 0,
                groundAt(0.425, 64.483, 0, 3, 1_100), 1_100,
                null, null, fullFloor(1_100));

        assertFalse(impactSettling.impossibleTakeoff(),
                "small grounded movement during knockback settling is not a fresh jump");
    }

    @Test void localizedBlockChangeSkipsOnlyCollisionOffsetAndPreservesSpeedEvidence() {
        var sequence = new GroundMotionSequence();
        var floor = fullFloor(1_000);
        sequence.accept(true, true, 0, 64, 0, 0,
                groundAt(0, 64, 0, 1, 1_000), 1_000, null, null, floor);
        sequence.accept(true, false, 0, 64, 0, 0,
                groundAt(0, 64, 0, 2, 1_050), 1_050, null, null, fullFloor(1_050));

        var moved = sequence.accept(true, false, 0.35, 64, 0, 0,
                groundAt(0.35, 64, 0, 3, 1_100), 1_100, null, null,
                fullFloor(1_100), true);

        assertTrue(moved.evaluated());
        assertEquals(0, moved.offset(), 0,
                "only the AABB-dependent prediction is skipped for the changed-block packet");
        assertTrue(moved.speedExcess() > 0.1,
                "the independent legal-speed signal must remain available");
    }

    @Test void localizedBlockChangeCanExplainAOnePacketStepWithinVanillaStepHeight() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0,
                groundAt(0, 64, 0, 1, 1_000), 1_000, null, null, fullFloor(1_000));
        sequence.accept(true, false, 0, 64, 0, 0,
                groundAt(0, 64, 0, 2, 1_050), 1_050, null, null, fullFloor(1_050));

        var stepped = sequence.accept(true, false, 0, 64.25, 0, 0,
                groundAt(0, 64.25, 0, 3, 1_100), 1_100, null, null,
                fullFloor(1_100), true);

        assertTrue(stepped.evaluated());
        assertEquals(0, stepped.offset(), 0);
        assertFalse(stepped.impossibleTakeoff(),
                "a changed nearby block can explain a step, but does not disable the speed envelope");
    }

    @Test void combatKnockbackStillUsesBlockAabbsWhenAnEntityPushIsNearby() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1_000), 1_000,
                null, null, fullFloor(1_000));
        var nearEntity = new MotionCollisionSnapshot(-4, 62, -4, 4, 68, 4,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(new MotionCollisionSnapshot.Box(-4, 63, -4, 4, 64, 4)),
                false, true, true, 1_050);

        var noKnockback = sequence.accept(true, false, 0, 64, 0, 0,
                ground(0, 2, 1_050), 1_050, null,
                new ExternalMotionTracker.Impulse(1, 0.8, 0, 0, 1_049, false, true), nearEntity);

        assertTrue(noKnockback.externalImpulseMismatch(), "entity push uncertainty must not mask a full cancelled velocity");
    }

    @Test void jumpInputDoesNotHideCancelledCombatKnockback() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, groundAt(0, 64, 0, 1, 1_000), 1_000,
                null, null, fullFloor(1_000));
        var jump = new MotionPredictor.Input(false, false, false, false,
                true, false, false);

        var cancelled = sequence.accept(true, false, 0, 64, 0, 0,
                groundAt(0, 64, 0, 2, 1_050), 1_050,
                new JavaInputCapture.Window(jump, null),
                new ExternalMotionTracker.Impulse(1, 0.2, 0, 0, 1_049, false, true),
                fullFloor(1_050));

        assertTrue(cancelled.externalImpulseMismatch(),
                "the jump-reset candidate must still retain the hit impulse when checking AntiKB");
    }

    @Test void verticalOnlyCombatKnockbackIsCheckedWhileStillGrounded() {
        var sequence = new GroundMotionSequence();
        sequence.accept(true, true, 0, 64, 0, 0, ground(0, 1, 1_000), 1_000,
                null, null, fullFloor(1_000));

        var noKnockback = sequence.accept(true, false, 0, 64, 0, 0,
                ground(0, 2, 1_050), 1_050, null,
                new ExternalMotionTracker.Impulse(1, 0, 0.42, 0, 1_049, false, true), fullFloor(1_050));

        assertTrue(noKnockback.externalImpulseMismatch(), "vertical velocity cancellation must not be hidden by a grounded snapshot");
    }

    @Test void sixteenthBlockPathEdgeIsSimulatedAsAnAabbStepInsteadOfAnImpossibleJump() {
        var sequence = new GroundMotionSequence();
        var pathCollision = pathEdgeCollision(1_000);
        assertTrue(pathCollision.resolve(-0.3, -0.0625, 0,
                0.1f * 0.98f, -0.08, 0, true).stream()
                .anyMatch(move -> Math.abs(move.x() - 0.1f * 0.98f) < 1.0e-6
                        && Math.abs(move.y() - 0.0625) < 1.0e-6));
        sequence.accept(true, true, -0.3, -0.0625, 0, 0,
                groundAt(-0.3, -0.0625, 0, 1, 1_000), 1_000,
                null, null, pathCollision);

        double step = 0.1f * 0.98f;
        var stepped = sequence.accept(true, false, -0.3 + step, 0, 0, 0,
                groundAt(-0.3 + step, 0, 0, 2, 1_050), 1_050,
                null, null, pathEdgeCollision(1_050));

        assertFalse(stepped.impossibleTakeoff(), "a real 1/16 block AABB transition is not a jump cheat");
    }

    private MotionCollisionSnapshot fullFloor(long at) {
        return new MotionCollisionSnapshot(-4, 62, -4, 4, 68, 4,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(new MotionCollisionSnapshot.Box(-4, 63, -4, 4, 64, 4)), true, at);
    }

    private MotionCollisionSnapshot pathEdgeCollision(long at) {
        return new MotionCollisionSnapshot(-2, -2, -2, 2, 4, 2,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(
                        new MotionCollisionSnapshot.Box(-1, -1, -1, 0, -0.0625, 1),
                        new MotionCollisionSnapshot.Box(0, -1, -1, 1, 0, 1)), true, at);
    }

    private MotionCollisionSnapshot stepCollision(double height, long at) {
        return new MotionCollisionSnapshot(-2, -2, -2, 2, 4, 2,
                0.6, 1.8, 0.6, 0.6,
                java.util.List.of(
                        new MotionCollisionSnapshot.Box(-2, -1, -2, 2, 0, 2),
                        new MotionCollisionSnapshot.Box(0, 0, -1, 1, height, 1)), true, at);
    }

    private MotionEnvironment.Snapshot groundAtSpeed(double x, double y, double z,
                                                     int tick, long at, double speed) {
        return new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, speed, x, y, z, tick, at, false, false);
    }
}
