package org.pexserver.pac.movement;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionCollisionSnapshotTest {
    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.8;

    @Test void densePartialBlockShapesGrowStepBufferWithoutCrashing() {
        List<MotionCollisionSnapshot.Box> shapes = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double bottom = i * 0.025;
            shapes.add(box(0.35, bottom, -0.1, 0.65, bottom + 0.01, 0.1));
        }
        var collision = snapshot(shapes);
        var moves = new MotionCollisionSnapshot.MoveBuffer();

        int count = collision.resolveInto(0, 0, 0, 0.2, 0, 0, true, moves);

        assertTrue(count > 0);
        assertEquals(collision.resolve(0, 0, 0, 0.2, 0, 0, true).size(), count);
    }

    @Test void baseCollisionMatchesMojangAxisClipOrderForWallsCornersAndPartialShapes() {
        List<List<MotionCollisionSnapshot.Box>> layouts = List.of(
                List.of(box(0.5, 0, -0.5, 1.5, 2, 0.5)),
                List.of(box(0.5, 0, 0.5, 1.5, 2, 1.5), box(-1.5, 0, 0.5, -0.5, 2, 1.5)),
                List.of(box(0.35, 0, -0.2, 0.85, 0.5, 0.2),
                        box(-0.2, 0, 0.35, 0.2, 1.25, 0.85)));
        List<Vec3> movements = List.of(
                new Vec3(0.5, 0, 0.2), new Vec3(-0.5, 0, 0.2),
                new Vec3(0.2, 0, 0.5), new Vec3(0.2, 0, -0.5),
                new Vec3(0.5, 0, 0.5), new Vec3(-0.5, 0, -0.5),
                new Vec3(0.5, 0.3, 0.5), new Vec3(-0.5, -0.3, 0.5));

        for (List<MotionCollisionSnapshot.Box> layout : layouts) {
            var snapshot = snapshot(layout);
            List<VoxelShape> shapes = layout.stream().map(MotionCollisionSnapshotTest::shape).toList();
            for (Vec3 movement : movements) {
                List<MotionCollisionSnapshot.Move> resolved = snapshot.resolve(0, 0, 0,
                        movement.x, movement.y, movement.z, false);
                assertFalse(resolved.isEmpty());
                Vec3 expected = vanillaClip(new AABB(-WIDTH / 2, 0, -WIDTH / 2,
                        WIDTH / 2, HEIGHT, WIDTH / 2), movement, shapes);
                MotionCollisionSnapshot.Move actual = resolved.getFirst();
                String context = "layout=" + layout + ", movement=" + movement
                        + ", actual=" + resolved + ", expected=" + expected;
                assertEquals(expected.x, actual.x(), 0.0, context + " x");
                assertEquals(expected.y, actual.y(), 0.0, context + " y");
                assertEquals(expected.z, actual.z(), 0.0, context + " z");
            }
        }
    }

    @Test void optimizedPrimitiveClipMatchesMojangForRandomAabbLayouts() {
        Random random = new Random(26_300);
        for (int iteration = 0; iteration < 160; iteration++) {
            int count = 1 + random.nextInt(8);
            List<MotionCollisionSnapshot.Box> layout = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                double minX = -1.5 + random.nextDouble() * 3;
                double minY = -0.5 + random.nextDouble() * 2.5;
                double minZ = -1.5 + random.nextDouble() * 3;
                layout.add(box(minX, minY, minZ,
                        minX + 0.05 + random.nextDouble() * 0.9,
                        minY + 0.05 + random.nextDouble() * 0.9,
                        minZ + 0.05 + random.nextDouble() * 0.9));
            }
            double dx = random.nextDouble() * 2.2 - 1.1;
            double dy = random.nextDouble() * 2.2 - 1.1;
            double dz = random.nextDouble() * 2.2 - 1.1;
            List<VoxelShape> shapes = layout.stream().map(MotionCollisionSnapshotTest::shape).toList();
            Vec3 expected = vanillaClip(new AABB(-WIDTH / 2, 0, -WIDTH / 2,
                    WIDTH / 2, HEIGHT, WIDTH / 2), new Vec3(dx, dy, dz), shapes);
            MotionCollisionSnapshot.Move actual = snapshot(layout).resolve(
                    0, 0, 0, dx, dy, dz, false).getFirst();

            assertEquals(expected.x, actual.x(), 0.0, "iteration=" + iteration + " x");
            assertEquals(expected.y, actual.y(), 0.0, "iteration=" + iteration + " y");
            assertEquals(expected.z, actual.z(), 0.0, "iteration=" + iteration + " z");
        }
    }

    @Test void duplicateCollisionBoxesDoNotChangeResolvedMovement() {
        var wall = box(0.5, 0, -0.5, 1.5, 2, 0.5);
        var unique = snapshot(List.of(wall));
        var duplicated = snapshot(List.of(wall, wall, wall));

        for (double dx : new double[] {-0.4, 0.2, 0.5, 1.0}) {
            for (double dz : new double[] {-0.4, 0.0, 0.4}) {
                assertEquals(unique.resolve(0, 0, 0, dx, 0, dz, false),
                        duplicated.resolve(0, 0, 0, dx, 0, dz, false));
            }
        }
    }

    @Test void diagonalCornerCollisionKeepsBothLegalHorizontalAxisOrders() {
        var corner = snapshot(List.of(box(0.35, 0, 0.35, 0.65, 2, 0.65)));

        var moves = corner.resolve(0, 0, 0, 0.2, 0, 0.2, false);

        assertEquals(2, moves.size());
        assertEquals(0.2, moves.get(0).x(), 1.0e-12);
        assertEquals(0, moves.get(0).y(), 1.0e-12);
        assertEquals(0.05, moves.get(0).z(), 1.0e-12);
        assertEquals(0.05, moves.get(1).x(), 1.0e-12);
        assertEquals(0, moves.get(1).y(), 1.0e-12);
        assertEquals(0.2, moves.get(1).z(), 1.0e-12);
    }

    @Test void bothHorizontalAxisOrdersMatchMojangForRandomBlockLayouts() {
        Random random = new Random(26_927);
        for (int iteration = 0; iteration < 180; iteration++) {
            List<MotionCollisionSnapshot.Box> layout = new java.util.ArrayList<>();
            int count = 1 + random.nextInt(8);
            for (int i = 0; i < count; i++) {
                double minX = -1.5 + random.nextDouble() * 3;
                double minY = -0.5 + random.nextDouble() * 2.5;
                double minZ = -1.5 + random.nextDouble() * 3;
                layout.add(box(minX, minY, minZ,
                        minX + 0.05 + random.nextDouble() * 0.9,
                        minY + 0.05 + random.nextDouble() * 0.9,
                        minZ + 0.05 + random.nextDouble() * 0.9));
            }
            double dx = (0.001 + random.nextDouble() * 1.1) * (random.nextBoolean() ? 1 : -1);
            double dy = random.nextDouble() * 2.2 - 1.1;
            double dz = (0.001 + random.nextDouble() * 1.1) * (random.nextBoolean() ? 1 : -1);
            List<VoxelShape> shapes = layout.stream().map(MotionCollisionSnapshotTest::shape).toList();
            AABB start = new AABB(-WIDTH / 2, 0, -WIDTH / 2,
                    WIDTH / 2, HEIGHT, WIDTH / 2);
            var actual = snapshot(layout).resolve(0, 0, 0, dx, dy, dz, false);

            assertContains(actual, vanillaClip(start, new Vec3(dx, dy, dz), shapes, false), iteration);
            assertContains(actual, vanillaClip(start, new Vec3(dx, dy, dz), shapes, true), iteration);
        }
    }

    @Test void missingCollisionCoverageIsNotReportedAsAValidClearMove() {
        var snapshot = new MotionCollisionSnapshot(-1, -1, -1, 1, 2, 1,
                WIDTH, HEIGHT, WIDTH, 0.6,
                List.of(box(0.5, 0, -0.5, 1.5, 2, 0.5)), true, 1_000);
        assertTrue(snapshot.resolve(0, 0, 0, 4, 0, 0, false).isEmpty());
        assertTrue(snapshot.resolve(0, 0, 0, Double.NaN, 0, 0, false).isEmpty());
    }

    @Test void reusableMoveBufferMatchesListApiAndClearsAfterAnInvalidQuery() {
        var snapshot = snapshot(List.of(
                box(0.5, 0, -0.5, 1.5, 2, 0.5),
                box(-1, -1, -1, 0, -0.0625, 1),
                box(0, -1, -1, 1, 0, 1)));
        var buffer = new MotionCollisionSnapshot.MoveBuffer();

        List<MotionCollisionSnapshot.Move> first = snapshot.resolve(0, 0, 0,
                0.5, 0, 0, false);
        int firstCount = snapshot.resolveInto(0, 0, 0, 0.5, 0, 0, false, buffer);
        assertMovesEqual(first, firstCount, buffer);

        assertEquals(0, snapshot.resolveInto(0, 0, 0, 100, 0, 0, false, buffer));
        List<MotionCollisionSnapshot.Move> stepped = snapshot.resolve(-0.3, -0.0625, 0,
                0.098, -0.08, 0, true);
        int steppedCount = snapshot.resolveInto(-0.3, -0.0625, 0,
                0.098, -0.08, 0, true, buffer);
        assertMovesEqual(stepped, steppedCount, buffer);
    }

    @Test void collisionBranchLowerBoundNeverExceedsAnyResolvedAabbOutcome() {
        Random random = new Random(26_926);
        for (int iteration = 0; iteration < 300; iteration++) {
            List<MotionCollisionSnapshot.Box> layout = new java.util.ArrayList<>();
            int shapes = random.nextInt(8);
            for (int i = 0; i < shapes; i++) {
                double x = -2 + random.nextDouble() * 4;
                double y = -1 + random.nextDouble() * 3;
                double z = -2 + random.nextDouble() * 4;
                layout.add(box(x, y, z, x + 0.1 + random.nextDouble(),
                        y + 0.1 + random.nextDouble(), z + 0.1 + random.nextDouble()));
            }
            var collisions = snapshot(layout);
            double startX = -1 + random.nextDouble() * 2;
            double startY = -0.5 + random.nextDouble();
            double startZ = -1 + random.nextDouble() * 2;
            double dx = -1 + random.nextDouble() * 2;
            double dy = -0.8 + random.nextDouble() * 1.6;
            double dz = -1 + random.nextDouble() * 2;
            boolean onGround = random.nextBoolean();
            double targetX = -1.5 + random.nextDouble() * 3;
            double targetY = -1 + random.nextDouble() * 2;
            double targetZ = -1.5 + random.nextDouble() * 3;
            double entityPushAllowance = random.nextInt(4) * 0.08;

            double lowerBound = MultiStepMotionPredictor.collisionResultLowerBound(
                    targetX, targetY, targetZ, dx, dy, dz, collisions.maxStep(), onGround, true,
                    entityPushAllowance);
            for (MotionCollisionSnapshot.Move move : collisions.resolve(startX, startY, startZ,
                    dx, dy, dz, onGround)) {
                double horizontal = MotionCollisionSnapshot.horizontalResidual(
                        targetX - move.x(), targetZ - move.z(), entityPushAllowance);
                double distance = Math.hypot(horizontal, targetY - move.y());
                assertTrue(lowerBound <= distance + 1.0e-12,
                        "lower bound=" + lowerBound + " exceeded resolved distance=" + distance
                                + " at iteration=" + iteration + ", move=" + move);
            }
        }
    }

    private static void assertMovesEqual(List<MotionCollisionSnapshot.Move> expected, int actualCount,
                                         MotionCollisionSnapshot.MoveBuffer actual) {
        assertEquals(expected.size(), actualCount);
        for (int i = 0; i < actualCount; i++) {
            assertEquals(expected.get(i).x(), actual.x(i), 0.0);
            assertEquals(expected.get(i).y(), actual.y(i), 0.0);
            assertEquals(expected.get(i).z(), actual.z(i), 0.0);
        }
    }

    @Test void ceilingAabbClipsTheVanillaJumpVectorAtTheHeadContact() {
        var ceiling = snapshot(List.of(box(-0.5, 2, -0.5, 0.5, 3, 0.5)));
        var resolved = ceiling.resolve(0, 0, 0, 0, 0.42, 0, false);
        assertEquals(1, resolved.size());
        assertEquals(0.2, resolved.getFirst().y(), 1.0e-12);
    }

    @Test void exactPathBlockAabbAllowsTheOneSixteenthStepOntoAdjacentFullBlock() {
        var pathAndGrass = snapshot(List.of(
                box(-1, -1, -1, 0, -0.0625, 1),
                box(0, -1, -1, 1, 0, 1)));

        var moves = pathAndGrass.resolve(-0.3, -0.0625, 0,
                0.098, -0.08, 0, true);

        assertTrue(moves.stream().anyMatch(move -> Math.abs(move.x() - 0.098) < 1.0e-9
                && Math.abs(move.y() - 0.0625) < 1.0e-9));
    }

    @Test void modernStepResolutionStopsAtTheFirstHeightThatImprovesHorizontalMovement() {
        var multipleSteps = snapshot(List.of(
                box(-2, -1, -2, 0, 0, 2),
                box(0, 0, -1, 0.3, 0.25, 1),
                box(0.3, 0.5, -1, 1, 0.6, 1)));

        var moves = multipleSteps.resolve(-0.3, 0, 0, 0.8, 0, 0, true);

        assertEquals(2, moves.size(), "base result plus the first successful step only");
        assertEquals(0, moves.get(0).x(), 1.0e-12);
        assertEquals(0.3, moves.get(1).x(), 1.0e-12);
        assertEquals(0.25, moves.get(1).y(), 1.0e-12);
    }

    @Test void legacyStepResolutionUsesFullStepAndVersionSpecificDownClip() {
        var multipleSteps = List.of(
                box(-2, -1, -2, 0, 0, 2),
                box(0, 0, -1, 0.3, 0.25, 1),
                box(0.3, 0.5, -1, 1, 0.6, 1));
        var legacy = snapshot(multipleSteps, MotionCollisionSnapshot.StepProfile.V1_14_TO_1_20);
        var oldLegacy = snapshot(multipleSteps, MotionCollisionSnapshot.StepProfile.V1_8_TO_1_13);

        var modern = snapshot(multipleSteps).resolve(-0.3, 0, 0, 0.8, 0, 0, true);
        var legacyMoves = legacy.resolve(-0.3, 0, 0, 0.8, 0, 0, true);
        var oldLegacyMoves = oldLegacy.resolve(-0.3, 0, 0, 0.8, 0, 0, true);

        assertEquals(0.3, modern.get(1).x(), 1.0e-12);
        assertEquals(0.8, legacyMoves.get(1).x(), 1.0e-12);
        assertEquals(0.6, legacyMoves.get(1).y(), 1.0e-12);
        assertEquals(0.8, oldLegacyMoves.get(1).x(), 1.0e-12);
        assertEquals(0.6, oldLegacyMoves.get(1).y(), 1.0e-12);
    }

    @Test void descendingStepCapturesHeadroomCollisionAboveTheOriginalPlayerBox() {
        var stepAndLowCeiling = snapshot(List.of(
                box(-2, -1, -2, 0, 0, 2),
                box(0, 0, -1, 0.3, 0.25, 1),
                box(0, 2.0, -1, 1, 2.4, 1)));

        var moves = stepAndLowCeiling.resolve(-0.3, 0.1, 0, 0.8, -0.2, 0, false);

        assertTrue(moves.stream().noneMatch(move -> move.x() > 0.01),
                "the headroom shape must prevent a step that the player AABB cannot fit under");
    }

    @Test void preAndPost114StepPathsApplyDifferentVerticalRemainders() {
        var ledge = List.of(
                box(-2, -1, -2, 0, 0, 2),
                box(0, 0, -1, 0.3, 0.6, 1));
        var pre114 = snapshot(ledge, MotionCollisionSnapshot.StepProfile.V1_8_TO_1_13);
        var post114 = snapshot(ledge, MotionCollisionSnapshot.StepProfile.V1_14_TO_1_20);
        var modern = snapshot(ledge);

        var pre114Step = pre114.resolve(-0.3, 0, 0, 1.1, 0.42, 0, true).get(1);
        var post114Step = post114.resolve(-0.3, 0, 0, 1.1, 0.42, 0, true).get(1);
        var modernStep = modern.resolve(-0.3, 0, 0, 1.1, 0.42, 0, true).get(1);

        assertEquals(0, pre114Step.y(), 1.0e-12);
        assertEquals(0.42, post114Step.y(), 1.0e-12);
        assertEquals((double) 0.6f, modernStep.y(), 1.0e-12);
    }

    @Test void legacyStepOutcomesContainGrimReferenceForRandomAabbLayouts() {
        Random random = new Random(26_314);
        for (int iteration = 0; iteration < 180; iteration++) {
            List<MotionCollisionSnapshot.Box> layout = new java.util.ArrayList<>();
            int count = 1 + random.nextInt(7);
            for (int i = 0; i < count; i++) {
                double minX = -1.5 + random.nextDouble() * 3;
                double minY = -0.5 + random.nextDouble() * 2;
                double minZ = -1.5 + random.nextDouble() * 3;
                layout.add(box(minX, minY, minZ,
                        minX + 0.05 + random.nextDouble() * 0.8,
                        minY + 0.05 + random.nextDouble() * 0.8,
                        minZ + 0.05 + random.nextDouble() * 0.8));
            }
            double startX = -0.4 + random.nextDouble() * 0.8;
            double startY = -0.1 + random.nextDouble() * 0.3;
            double startZ = -0.4 + random.nextDouble() * 0.8;
            double dx = (0.05 + random.nextDouble()) * (random.nextBoolean() ? 1 : -1);
            double dy = -0.25 + random.nextDouble() * 0.7;
            double dz = (0.05 + random.nextDouble()) * (random.nextBoolean() ? 1 : -1);
            AABB start = new AABB(startX - WIDTH / 2, startY, startZ - WIDTH / 2,
                    startX + WIDTH / 2, startY + HEIGHT, startZ + WIDTH / 2);
            List<VoxelShape> shapes = layout.stream().map(MotionCollisionSnapshotTest::shape).toList();

            for (MotionCollisionSnapshot.StepProfile profile : List.of(
                    MotionCollisionSnapshot.StepProfile.V1_8_TO_1_13,
                    MotionCollisionSnapshot.StepProfile.V1_14_TO_1_20)) {
                var actual = snapshot(layout, profile).resolve(startX, startY, startZ,
                        dx, dy, dz, true);
                for (boolean zBeforeX : new boolean[] {false, true}) {
                    Vec3 expected = grimLegacyStep(start, new Vec3(dx, dy, dz), shapes,
                            zBeforeX, profile == MotionCollisionSnapshot.StepProfile.V1_14_TO_1_20);
                    if (expected != null) assertContains(actual, expected, iteration);
                }
            }
        }
    }

    @Test void blockAabbsStayUsableForImpulseResponseWhenAnEntityPushIsUnmodeled() {
        var snapshot = new MotionCollisionSnapshot(-4, -4, -4, 4, 4, 4,
                WIDTH, HEIGHT, WIDTH, 0.6,
                List.of(box(0.5, 0, -0.5, 1.5, 2, 0.5)),
                false, true, true, 1_000);

        assertFalse(snapshot.complete());
        assertTrue(snapshot.blockGeometryComplete());
        assertTrue(snapshot.entityPushPossible());
        assertEquals(0.2, snapshot.resolve(0, 0, 0, 0.5, 0, 0, false).getFirst().x(), 1e-12);
    }

    @Test void entityPushAllowanceScalesWithRecentContactCountAndIsCapped() {
        var oneEntity = new MotionCollisionSnapshot(-4, -4, -4, 4, 4, 4,
                WIDTH, HEIGHT, WIDTH, 0.6, List.of(), false, true, 1, 1_000);
        var manyEntities = new MotionCollisionSnapshot(-4, -4, -4, 4, 4, 4,
                WIDTH, HEIGHT, WIDTH, 0.6, List.of(), false, true, 8, 1_000);

        assertEquals(1, oneEntity.entityPushCount());
        assertEquals(0.08, oneEntity.entityPushHorizontalAllowance(), 1e-12);
        assertEquals(8, manyEntities.entityPushCount());
        assertEquals(0.24, manyEntities.entityPushHorizontalAllowance(), 1e-12);
    }

    @Test void entityPushUncertaintyIsAppliedPerHorizontalAxis() {
        assertEquals(0, MotionCollisionSnapshot.horizontalResidual(0.08, -0.08, 0.08), 1e-12);
        assertEquals(0.08, MotionCollisionSnapshot.horizontalResidual(0.16, 0.08, 0.08), 1e-12);
        assertEquals(Math.sqrt(2) * 0.08,
                MotionCollisionSnapshot.horizontalVectorAllowance(0.08), 1e-12);
    }

    private static Vec3 vanillaClip(AABB box, Vec3 movement, List<VoxelShape> shapes) {
        Vec3 result = Vec3.ZERO;
        for (Direction.Axis axis : Direction.axisStepOrder(movement)) {
            double amount = movement.get(axis);
            if (amount != 0) result = result.with(axis,
                    Shapes.collide(axis, box.move(result), shapes, amount));
        }
        return result;
    }

    private static Vec3 vanillaClip(AABB box, Vec3 movement, List<VoxelShape> shapes,
                                    boolean zBeforeX) {
        Vec3 result = Vec3.ZERO;
        for (Direction.Axis axis : zBeforeX
                ? List.of(Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X)
                : List.of(Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z)) {
            double amount = movement.get(axis);
            if (amount != 0) result = result.with(axis,
                    Shapes.collide(axis, box.move(result), shapes, amount));
        }
        return result;
    }

    private static Vec3 grimLegacyStep(AABB box, Vec3 desired, List<VoxelShape> shapes,
                                       boolean zBeforeX, boolean post114) {
        Vec3 collision = vanillaClip(box, desired, shapes, zBeforeX);
        boolean horizontalCollision = collision.x != desired.x || collision.z != desired.z;
        if (!horizontalCollision) return null;

        double maxStep = 0.6;
        Vec3 step = vanillaClip(box, new Vec3(desired.x, maxStep, desired.z), shapes, zBeforeX);
        double minX = desired.x < 0 ? box.minX + desired.x : box.minX;
        double maxX = desired.x > 0 ? box.maxX + desired.x : box.maxX;
        double minZ = desired.z < 0 ? box.minZ + desired.z : box.minZ;
        double maxZ = desired.z > 0 ? box.maxZ + desired.z : box.maxZ;
        AABB expanded = new AABB(minX, box.minY, minZ, maxX, box.maxY, maxZ);
        double bugFixY = Shapes.collide(Direction.Axis.Y, expanded, shapes, maxStep);
        if (bugFixY < maxStep) {
            Vec3 horizontal = vanillaClip(box.move(0, bugFixY, 0),
                    new Vec3(desired.x, 0, desired.z), shapes, zBeforeX);
            Vec3 fixed = new Vec3(horizontal.x, bugFixY, horizontal.z);
            if (fixed.x * fixed.x + fixed.z * fixed.z > step.x * step.x + step.z * step.z)
                step = fixed;
        }

        if (step.x * step.x + step.z * step.z <= collision.x * collision.x + collision.z * collision.z)
            return null;
        double down = -step.y + (post114 ? desired.y : 0);
        double clippedDown = Shapes.collide(Direction.Axis.Y,
                box.move(step.x, step.y, step.z), shapes, down);
        return new Vec3(step.x, step.y + clippedDown, step.z);
    }

    private static void assertContains(List<MotionCollisionSnapshot.Move> actual, Vec3 expected,
                                       int iteration) {
        assertTrue(actual.stream().anyMatch(move -> Math.abs(move.x() - expected.x) < 1.0e-12
                        && Math.abs(move.y() - expected.y) < 1.0e-12
                        && Math.abs(move.z() - expected.z) < 1.0e-12),
                "missing Mojang axis-order result at iteration=" + iteration + ": expected="
                        + expected + ", actual=" + actual);
    }

    private static MotionCollisionSnapshot snapshot(List<MotionCollisionSnapshot.Box> shapes) {
        return snapshot(shapes, MotionCollisionSnapshot.StepProfile.V1_21_PLUS);
    }

    private static MotionCollisionSnapshot snapshot(List<MotionCollisionSnapshot.Box> shapes,
                                                    MotionCollisionSnapshot.StepProfile profile) {
        return new MotionCollisionSnapshot(-4, -4, -4, 4, 4, 4,
                WIDTH, HEIGHT, WIDTH, 0.6, shapes, true, true, 0, profile, 1_000);
    }

    private static VoxelShape shape(MotionCollisionSnapshot.Box box) {
        return Shapes.create(new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ()));
    }

    private static MotionCollisionSnapshot.Box box(double minX, double minY, double minZ,
                                                   double maxX, double maxY, double maxZ) {
        return new MotionCollisionSnapshot.Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static double square(double value) { return value * value; }
}
