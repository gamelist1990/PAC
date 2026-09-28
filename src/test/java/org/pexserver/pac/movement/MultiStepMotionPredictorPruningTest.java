package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MultiStepMotionPredictorPruningTest {
    @Test void deterministicKnownInputReplayReturnsExactMultiFramePrediction() {
        var environment = new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, 0, 0, 0, 1, 1_000);
        var collisions = new MotionCollisionSnapshot(-8, 56, -8, 8, 72, 8,
                0.6, 1.8, 0.6, 0.6, List.of(), true, 1_000);
        var input = new MotionPredictor.Input(true, false, false, false, false, false, false);
        var frames = new ArrayList<MultiStepMotionPredictor.Frame>();
        for (int i = 0; i < 30; i++)
            frames.add(new MultiStepMotionPredictor.Frame(0, List.of(input), environment, false, collisions));

        MotionPredictor.Motion velocity = new MotionPredictor.Motion(0, 0, 0);
        double x = 0, z = 0;
        for (var frame : frames) {
            velocity = MotionPredictor.predictGroundInputClient(velocity,
                    new MotionPredictor.Motion(0, 0, 0), frame.yaw(),
                    environment.movementSpeed(), environment.groundFriction(),
                    environment.horizontalDrag(), environment.sneaking() ? environment.sneakingSpeed() : 1.0f,
                    environment.itemUseMultiplier(), input).closest();
            x += velocity.dx();
            z += velocity.dz();
        }

        var replay = MultiStepMotionPredictor.ground3d(new MotionPredictor.Motion(0, 0, 0),
                x, 0, z, frames, 0, 64, 0);

        assertNotNull(replay);
        assertEquals(0, replay.offset(), 1.0e-12,
                "target=" + x + "," + z + " final=" + replay.finalVelocity());
        assertEquals(velocity.dx(), replay.finalVelocity().dx(), 1.0e-12);
        assertEquals(velocity.dz(), replay.finalVelocity().dz(), 1.0e-12);
    }

    @Test void deterministicInputsStillExploreDistinctCornerCollisionOrders() {
        var environment = new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.3, 0, 0, 0, 1, 1_000);
        var collisions = new MotionCollisionSnapshot(-8, -8, -8, 8, 8, 8,
                0.6, 1.8, 0.6, 0.6,
                List.of(new MotionCollisionSnapshot.Box(0.35, 0, 0.35, 0.65, 2, 0.65)),
                true, 1_000);
        var input = new MotionPredictor.Input(true, false, false, false, false, false, false);
        var frame = new MultiStepMotionPredictor.Frame(-45, List.of(input), environment, false, collisions);
        MotionPredictor.Motion velocity = MotionPredictor.predictGroundInputClient(
                new MotionPredictor.Motion(0, 0, 0), new MotionPredictor.Motion(0, 0, 0),
                frame.yaw(), environment.movementSpeed(), environment.groundFriction(),
                environment.horizontalDrag(), environment.sneaking() ? environment.sneakingSpeed() : 1.0f,
                environment.itemUseMultiplier(), input).closest();
        List<MotionCollisionSnapshot.Move> outcomes = collisions.resolve(0, 0, 0,
                velocity.dx(), 0, velocity.dz(), true);
        assertEquals(2, outcomes.size());

        var replay = MultiStepMotionPredictor.ground3d(new MotionPredictor.Motion(0, 0, 0),
                outcomes.getLast().x(), 0, outcomes.getLast().z(), List.of(frame), 0, 0, 0);

        assertNotNull(replay);
        assertEquals(0, replay.offset(), 1.0e-12);
    }

    @Test void boundedCollisionPruningPreservesTheExactBestOneFramePrediction() {
        Random random = new Random(26_927);
        for (int iteration = 0; iteration < 250; iteration++) {
            List<MotionCollisionSnapshot.Box> boxes = new ArrayList<>();
            int shapeCount = random.nextInt(9);
            for (int i = 0; i < shapeCount; i++) {
                double x = -1.5 + random.nextDouble() * 3;
                double y = -0.5 + random.nextDouble() * 2.5;
                double z = -1.5 + random.nextDouble() * 3;
                boxes.add(new MotionCollisionSnapshot.Box(x, y, z,
                        x + 0.1 + random.nextDouble() * 0.8,
                        y + 0.1 + random.nextDouble() * 0.8,
                        z + 0.1 + random.nextDouble() * 0.8));
            }
            var collision = new MotionCollisionSnapshot(-8, -8, -8, 8, 8, 8,
                    0.6, 1.8, 0.6, 0.6, boxes, true, 1_000);
            var environment = new MotionEnvironment.Snapshot(true, false, false, false,
                    0, 0.1, 0, 0, 0, 1, 1_000);
            var frame = MultiStepMotionPredictor.frame(0, null, environment, collision);
            var initial = new MotionPredictor.Motion(random.nextDouble() * 0.4 - 0.2,
                    0, random.nextDouble() * 0.4 - 0.2);
            double actualX = random.nextDouble() * 1.6 - 0.8;
            double actualY = random.nextDouble() * 0.5 - 0.25;
            double actualZ = random.nextDouble() * 1.6 - 0.8;

            double exhaustiveBest = Double.POSITIVE_INFINITY;
            for (MotionPredictor.Input input : frame.inputs()) {
                MotionPredictor.Motion free = MotionPredictor.predictGroundInputClient(initial,
                        new MotionPredictor.Motion(0, 0, 0), frame.yaw(), environment.movementSpeed(),
                        environment.groundFriction(), environment.horizontalDrag(), 1,
                        environment.itemUseMultiplier(), input).closest();
                for (MotionCollisionSnapshot.Move move : collision.resolve(0, 0, 0,
                        free.dx(), 0, free.dz(), true)) {
                    double dx = actualX - move.x();
                    double dy = actualY - move.y();
                    double dz = actualZ - move.z();
                    exhaustiveBest = Math.min(exhaustiveBest, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }

            var predicted = MultiStepMotionPredictor.ground3d(initial, actualX, actualY, actualZ,
                    List.of(frame), 0, 0, 0);
            assertNotNull(predicted, "iteration=" + iteration);
            assertEquals(exhaustiveBest, predicted.offset(), 1.0e-12,
                    "pruned search changed the exact best result at iteration=" + iteration);
        }
    }

    @Test void finalFramePruningPreservesTheExhaustiveMultiFrameBestPath() {
        Random random = new Random(26_928);
        for (int iteration = 0; iteration < 120; iteration++) {
            var environment = new MotionEnvironment.Snapshot(true, false, false, false,
                    0, 0.1, 0, 0, 0, 1, 1_000);
            var firstCollision = randomSnapshot(random);
            var lastCollision = randomSnapshot(random);
            var firstFrame = MultiStepMotionPredictor.frame(0, null, environment, firstCollision);
            var lastFrame = MultiStepMotionPredictor.frame(0, null, environment, lastCollision);
            var frames = List.of(firstFrame, lastFrame);
            var initial = new MotionPredictor.Motion(random.nextDouble() * 0.4 - 0.2,
                    0, random.nextDouble() * 0.4 - 0.2);
            double actualX = random.nextDouble() * 2.2 - 1.1;
            double actualY = random.nextDouble() * 0.7 - 0.35;
            double actualZ = random.nextDouble() * 2.2 - 1.1;

            double exhaustiveBest = Double.POSITIVE_INFINITY;
            for (MotionPredictor.Input firstInput : firstFrame.inputs()) {
                MotionPredictor.Motion firstFree = predictGround(initial, firstFrame, firstInput);
                for (MotionCollisionSnapshot.Move firstMove : firstCollision.resolve(0, 0, 0,
                        firstFree.dx(), 0, firstFree.dz(), true)) {
                    MotionPredictor.Motion nextVelocity = new MotionPredictor.Motion(
                            firstMove.x() == firstFree.dx() ? firstFree.dx() : 0,
                            0,
                            firstMove.z() == firstFree.dz() ? firstFree.dz() : 0);
                    for (MotionPredictor.Input lastInput : lastFrame.inputs()) {
                        MotionPredictor.Motion lastFree = predictGround(nextVelocity, lastFrame, lastInput);
                        for (MotionCollisionSnapshot.Move lastMove : lastCollision.resolve(
                                firstMove.x(), firstMove.y(), firstMove.z(),
                                lastFree.dx(), 0, lastFree.dz(), true)) {
                            double dx = actualX - firstMove.x() - lastMove.x();
                            double dy = actualY - firstMove.y() - lastMove.y();
                            double dz = actualZ - firstMove.z() - lastMove.z();
                            exhaustiveBest = Math.min(exhaustiveBest,
                                    Math.sqrt(dx * dx + dy * dy + dz * dz));
                        }
                    }
                }
            }

            var predicted = MultiStepMotionPredictor.ground3d(initial, actualX, actualY, actualZ,
                    frames, 0, 0, 0);
            assertNotNull(predicted, "iteration=" + iteration);
            assertEquals(exhaustiveBest, predicted.offset(), 1.0e-12,
                    "final-frame pruning changed the multi-frame best at iteration=" + iteration);
        }
    }

    @Test void entityPushAllowanceKeepsPruningEquivalentToExhaustiveReplay() {
        Random random = new Random(26_929);
        var environment = new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, 0, 0, 0, 1, 1_000);
        for (int iteration = 0; iteration < 24; iteration++) {
            int firstContacts = 1 + random.nextInt(4);
            int lastContacts = 1 + random.nextInt(4);
            var firstCollision = randomSnapshot(random, firstContacts);
            var lastCollision = randomSnapshot(random, lastContacts);
            var firstFrame = MultiStepMotionPredictor.frame(0, null, environment, firstCollision);
            var lastFrame = MultiStepMotionPredictor.frame(0, null, environment, lastCollision);
            var frames = List.of(firstFrame, lastFrame);
            var initial = new MotionPredictor.Motion(random.nextDouble() * 0.4 - 0.2,
                    0, random.nextDouble() * 0.4 - 0.2);
            double actualX = random.nextDouble() * 2.2 - 1.1;
            double actualY = random.nextDouble() * 0.7 - 0.35;
            double actualZ = random.nextDouble() * 2.2 - 1.1;
            double allowance = Math.min(0.24, Math.max(firstContacts, lastContacts) * 0.08);

            double exhaustiveBest = Double.POSITIVE_INFINITY;
            for (MotionPredictor.Input firstInput : firstFrame.inputs()) {
                MotionPredictor.Motion firstFree = predictGround(initial, firstFrame, firstInput);
                for (MotionCollisionSnapshot.Move firstMove : firstCollision.resolve(0, 0, 0,
                        firstFree.dx(), 0, firstFree.dz(), true)) {
                    MotionPredictor.Motion nextVelocity = new MotionPredictor.Motion(
                            firstMove.x() == firstFree.dx() ? firstFree.dx() : 0,
                            0,
                            firstMove.z() == firstFree.dz() ? firstFree.dz() : 0);
                    for (MotionPredictor.Input lastInput : lastFrame.inputs()) {
                        MotionPredictor.Motion lastFree = predictGround(nextVelocity, lastFrame, lastInput);
                        for (MotionCollisionSnapshot.Move lastMove : lastCollision.resolve(
                                firstMove.x(), firstMove.y(), firstMove.z(),
                                lastFree.dx(), 0, lastFree.dz(), true)) {
                            double dx = actualX - firstMove.x() - lastMove.x();
                            double dy = actualY - firstMove.y() - lastMove.y();
                            double dz = actualZ - firstMove.z() - lastMove.z();
                            double horizontal = MotionCollisionSnapshot.horizontalResidual(dx, dz, allowance);
                            exhaustiveBest = Math.min(exhaustiveBest, Math.hypot(horizontal, dy));
                        }
                    }
                }
            }

            var predicted = MultiStepMotionPredictor.ground3d(initial, actualX, actualY, actualZ,
                    frames, 0, 0, 0);
            assertNotNull(predicted, "iteration=" + iteration);
            assertEquals(exhaustiveBest, predicted.offset(), 1.0e-12,
                    "entity allowance pruning changed the exact best result at iteration=" + iteration);
        }
    }

    @Test void collisionResolvedAirReplayCoversTerrainWithTrustedVerticalAndBlockGeometry() {
        var environment = new MotionEnvironment.Snapshot(false, false, false, false, false,
                0, 0.1, 0, 64, 0, 1, 1_000,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f,
                false, -1, true, 0.3f, 1.0f, 1.0f, 1.0f);
        var collisions = new MotionCollisionSnapshot(-8, 56, -8, 8, 72, 8,
                0.6, 1.8, 0.6, 0.6, List.of(), true, 1_000);
        var frame = MultiStepMotionPredictor.frame(0, null, environment, collisions);

        var replay = MultiStepMotionPredictor.air(new MotionPredictor.Motion(0, 0, 0),
                0, 0, List.of(frame), 0, 64, 0);

        assertNotNull(replay);
        assertEquals(0, replay.offset(), 1.0e-12);
    }

    @Test void collisionResolvedAirReplayRejectsTerrainWithoutTrustedBlockGeometry() {
        var environment = new MotionEnvironment.Snapshot(false, false, false, false, false,
                0, 0.1, 0, 64, 0, 1, 1_000,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f,
                false, -1, true, 0.3f, 1.0f, 1.0f, 1.0f);
        var frame = MultiStepMotionPredictor.frame(0, null, environment);

        var replay = MultiStepMotionPredictor.air(new MotionPredictor.Motion(0, 0, 0),
                0, 0, List.of(frame), 0, 64, 0);

        assertNull(replay);
    }

    private static MotionPredictor.Motion predictGround(MotionPredictor.Motion previous,
                                                         MultiStepMotionPredictor.Frame frame,
                                                         MotionPredictor.Input input) {
        var environment = frame.environment();
        return MotionPredictor.predictGroundInputClient(previous,
                new MotionPredictor.Motion(0, 0, 0), frame.yaw(), environment.movementSpeed(),
                environment.groundFriction(), environment.horizontalDrag(), 1,
                environment.itemUseMultiplier(), input).closest();
    }

    private static MotionCollisionSnapshot randomSnapshot(Random random) {
        return randomSnapshot(random, 0);
    }

    private static MotionCollisionSnapshot randomSnapshot(Random random, int entityPushCount) {
        List<MotionCollisionSnapshot.Box> boxes = new ArrayList<>();
        int shapeCount = random.nextInt(9);
        for (int i = 0; i < shapeCount; i++) {
            double x = -1.5 + random.nextDouble() * 3;
            double y = -0.5 + random.nextDouble() * 2.5;
            double z = -1.5 + random.nextDouble() * 3;
            boxes.add(new MotionCollisionSnapshot.Box(x, y, z,
                    x + 0.1 + random.nextDouble() * 0.8,
                    y + 0.1 + random.nextDouble() * 0.8,
                    z + 0.1 + random.nextDouble() * 0.8));
        }
        return new MotionCollisionSnapshot(-8, -8, -8, 8, 8, 8,
                0.6, 1.8, 0.6, 0.6, boxes, true, true, entityPushCount, 1_000);
    }
}
