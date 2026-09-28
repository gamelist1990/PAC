package org.pexserver.pac.movement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

    /** Immutable block-collision data sampled on the server thread for packet-thread simulation. */
public final class MotionCollisionSnapshot {
    /** Client-version collision stepping changes that alter legal movement outcomes. */
    public enum StepProfile {
        PRE_1_8,
        V1_8_TO_1_13,
        V1_14_TO_1_20,
        V1_21_PLUS
    }

    public record Box(double minX, double minY, double minZ,
                      double maxX, double maxY, double maxZ) { }
    public record EntityPush(double x, double z) { }
    public record Move(double x, double y, double z) { }

    static EntityPush officialEntityPush(double playerX, double playerZ,
                                         double entityX, double entityZ) {
        double dx = entityX - playerX;
        double dz = entityZ - playerZ;
        double distance = Math.max(Math.abs(dx), Math.abs(dz));
        if (!finite(dx, dz) || distance < 0.01) return null;
        double scale = Math.min(1.0, 1.0 / Math.sqrt(distance)) * 0.05;
        return new EntityPush(-dx / distance * scale, -dz / distance * scale);
    }

    /** Reusable primitive output for the prediction hot path. */
    public static final class MoveBuffer {
        private double[] x = new double[4];
        private double[] y = new double[4];
        private double[] z = new double[4];
        private int size;

        public int size() { return size; }
        public double x(int index) { return x[index]; }
        public double y(int index) { return y[index]; }
        public double z(int index) { return z[index]; }

        public void clear() { size = 0; }

        public void setSingle(double x, double y, double z) {
            size = 0;
            add(x, y, z);
        }

        private void add(double nextX, double nextY, double nextZ) {
            ensureCapacity(size + 1);
            x[size] = nextX;
            y[size] = nextY;
            z[size] = nextZ;
            size++;
        }

        private void addIfAbsent(double nextX, double nextY, double nextZ) {
            for (int i = 0; i < size; i++) {
                if (x[i] == nextX && y[i] == nextY && z[i] == nextZ) return;
            }
            add(nextX, nextY, nextZ);
        }

        private void ensureCapacity(int capacity) {
            if (capacity <= x.length) return;
            int grown = Math.max(capacity, x.length * 2);
            x = Arrays.copyOf(x, grown);
            y = Arrays.copyOf(y, grown);
            z = Arrays.copyOf(z, grown);
        }
    }

    private static final int MAX_GRID_CELLS = 1_000_000;

    private static final class CollisionScratch {
        private int[] seen = new int[0];
        private int[] nearbyShapeIds = new int[0];
        private int visitStamp;
        private int nearbyCount;
        private float[] stepHeights = new float[16];
        private int stepHeightCount;
        private final double[] clippedMove = new double[3];

        private void beginVisit(int shapeCount) {
            if (seen.length < shapeCount) seen = new int[shapeCount];
            if (nearbyShapeIds.length < shapeCount) nearbyShapeIds = new int[shapeCount];
            if (++visitStamp == 0) {
                Arrays.fill(seen, 0);
                visitStamp = 1;
            }
            nearbyCount = 0;
        }

        private void addShape(int shapeId) {
            if (seen[shapeId] == visitStamp) return;
            seen[shapeId] = visitStamp;
            nearbyShapeIds[nearbyCount++] = shapeId;
        }

        private void ensureStepHeightCapacity(int capacity) {
            if (capacity > stepHeights.length)
                stepHeights = Arrays.copyOf(stepHeights, Math.max(capacity, stepHeights.length * 2));
        }
    }

    private static final ThreadLocal<CollisionScratch> SCRATCH =
            ThreadLocal.withInitial(CollisionScratch::new);
    private static final ThreadLocal<MoveBuffer> PREDICTION_MOVES =
            ThreadLocal.withInitial(MoveBuffer::new);

    /** Reusable per-thread output for nested prediction loops that consume each result immediately. */
    static MoveBuffer predictionBuffer() { return PREDICTION_MOVES.get(); }

    private final double minX, minY, minZ, maxX, maxY, maxZ;
    private final double width, height, depth, maxStep;
    private final int minCellX, minCellY, minCellZ;
    private final int cellsX, cellsY, cellsZ;
    private final Box[] shapes;
    private final int[] cellShapeOffsets;
    private final int[] cellShapeIds;
    private final boolean complete, blockGeometryComplete, entityPushPossible, hardEntityCollisionPossible;
    private final int entityPushCount;
    private final List<EntityPush> entityPushes;
    private final StepProfile stepProfile;
    private final long capturedAt;

    public MotionCollisionSnapshot(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ,
                                   double width, double height, double depth, double maxStep,
                                   List<Box> shapes, boolean complete, long capturedAt) {
        this(minX, minY, minZ, maxX, maxY, maxZ, width, height, depth, maxStep,
                shapes, complete, complete, 0, StepProfile.V1_21_PLUS, capturedAt);
    }

    public MotionCollisionSnapshot(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ,
                                   double width, double height, double depth, double maxStep,
                                   List<Box> shapes, boolean complete,
                                   boolean blockGeometryComplete, boolean entityPushPossible,
                                   long capturedAt) {
        this(minX, minY, minZ, maxX, maxY, maxZ, width, height, depth, maxStep,
                shapes, complete, blockGeometryComplete, entityPushPossible ? 1 : 0,
                StepProfile.V1_21_PLUS, capturedAt);
    }

    public MotionCollisionSnapshot(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ,
                                   double width, double height, double depth, double maxStep,
                                   List<Box> shapes, boolean complete,
                                   boolean blockGeometryComplete, int entityPushCount,
                                   long capturedAt) {
        this(minX, minY, minZ, maxX, maxY, maxZ, width, height, depth, maxStep,
                shapes, complete, blockGeometryComplete, entityPushCount,
                StepProfile.V1_21_PLUS, capturedAt);
    }

    public MotionCollisionSnapshot(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ,
                                   double width, double height, double depth, double maxStep,
                                   List<Box> shapes, boolean complete,
                                   boolean blockGeometryComplete, int entityPushCount,
                                   StepProfile stepProfile, long capturedAt) {
        this(minX, minY, minZ, maxX, maxY, maxZ, width, height, depth, maxStep,
                shapes, complete, blockGeometryComplete, entityPushCount, false,
                stepProfile, capturedAt);
    }

    public MotionCollisionSnapshot(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ,
                                   double width, double height, double depth, double maxStep,
                                   List<Box> shapes, boolean complete,
                                   boolean blockGeometryComplete, int entityPushCount,
                                   boolean hardEntityCollisionPossible,
                                   StepProfile stepProfile, long capturedAt) {
                    this(minX, minY, minZ, maxX, maxY, maxZ, width, height, depth, maxStep,
                        shapes, complete, blockGeometryComplete, entityPushCount,
                        hardEntityCollisionPossible, stepProfile, capturedAt, List.of());
                    }

                    public MotionCollisionSnapshot(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ,
                                   double width, double height, double depth, double maxStep,
                                   List<Box> shapes, boolean complete,
                                   boolean blockGeometryComplete, int entityPushCount,
                                   boolean hardEntityCollisionPossible,
                                   StepProfile stepProfile, long capturedAt,
                                   List<EntityPush> entityPushes) {
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        this.width = width; this.height = height; this.depth = depth; this.maxStep = maxStep;
        this.stepProfile = stepProfile == null ? StepProfile.V1_21_PLUS : stepProfile;

        boolean finiteBounds = finite(minX, minY, minZ, maxX, maxY, maxZ)
                && maxX >= minX && maxY >= minY && maxZ >= minZ;
        int lowX = finiteBounds ? floor(minX) : 0;
        int lowY = finiteBounds ? floor(minY) : 0;
        int lowZ = finiteBounds ? floor(minZ) : 0;
        int sizeX = finiteBounds ? floor(maxX) - lowX + 1 : 0;
        int sizeY = finiteBounds ? floor(maxY) - lowY + 1 : 0;
        int sizeZ = finiteBounds ? floor(maxZ) - lowZ + 1 : 0;
        long gridCells = (long) sizeX * sizeY * sizeZ;
        boolean usableGrid = finiteBounds && sizeX > 0 && sizeY > 0 && sizeZ > 0
                && gridCells > 0 && gridCells <= MAX_GRID_CELLS;

        this.minCellX = lowX;
        this.minCellY = lowY;
        this.minCellZ = lowZ;
        this.cellsX = usableGrid ? sizeX : 0;
        this.cellsY = usableGrid ? sizeY : 0;
        this.cellsZ = usableGrid ? sizeZ : 0;

        // Store the collision geometry directly. Repeated boxes are harmless to
        // axis clipping, while hash-set nodes on every player snapshot add churn.
        this.shapes = usableGrid ? shapes.toArray(Box[]::new) : new Box[0];
        if (usableGrid) {
            int cellCount = (int) gridCells;
            int[] offsets = new int[cellCount + 1];
            for (int shapeId = 0; shapeId < this.shapes.length; shapeId++) {
                Box shape = this.shapes[shapeId];
                int shapeMinX = Math.max(lowX, floor(shape.minX()));
                int shapeMaxX = Math.min(lowX + sizeX - 1, floor(shape.maxX()));
                int shapeMinY = Math.max(lowY, floor(shape.minY()));
                int shapeMaxY = Math.min(lowY + sizeY - 1, floor(shape.maxY()));
                int shapeMinZ = Math.max(lowZ, floor(shape.minZ()));
                int shapeMaxZ = Math.min(lowZ + sizeZ - 1, floor(shape.maxZ()));
                for (int x = shapeMinX; x <= shapeMaxX; x++) {
                    for (int y = shapeMinY; y <= shapeMaxY; y++) {
                        for (int z = shapeMinZ; z <= shapeMaxZ; z++) {
                            int cell = cellIndex(x, y, z, lowX, lowY, lowZ, sizeY, sizeZ);
                            offsets[cell + 1]++;
                        }
                    }
                }
            }
            for (int cell = 0; cell < cellCount; cell++)
                offsets[cell + 1] += offsets[cell];
            int[] shapeIds = new int[offsets[cellCount]];
            int[] cursors = Arrays.copyOf(offsets, cellCount);
            for (int shapeId = 0; shapeId < this.shapes.length; shapeId++) {
                Box shape = this.shapes[shapeId];
                int shapeMinX = Math.max(lowX, floor(shape.minX()));
                int shapeMaxX = Math.min(lowX + sizeX - 1, floor(shape.maxX()));
                int shapeMinY = Math.max(lowY, floor(shape.minY()));
                int shapeMaxY = Math.min(lowY + sizeY - 1, floor(shape.maxY()));
                int shapeMinZ = Math.max(lowZ, floor(shape.minZ()));
                int shapeMaxZ = Math.min(lowZ + sizeZ - 1, floor(shape.maxZ()));
                for (int x = shapeMinX; x <= shapeMaxX; x++) {
                    for (int y = shapeMinY; y <= shapeMaxY; y++) {
                        for (int z = shapeMinZ; z <= shapeMaxZ; z++) {
                            int cell = cellIndex(x, y, z, lowX, lowY, lowZ, sizeY, sizeZ);
                            shapeIds[cursors[cell]++] = shapeId;
                        }
                    }
                }
            }
            this.cellShapeOffsets = offsets;
            this.cellShapeIds = shapeIds;
        } else {
            this.cellShapeOffsets = new int[0];
            this.cellShapeIds = new int[0];
        }
        this.complete = complete && usableGrid;
        this.blockGeometryComplete = blockGeometryComplete && usableGrid;
        this.entityPushCount = Math.max(0, entityPushCount);
        this.entityPushPossible = this.entityPushCount > 0;
        this.hardEntityCollisionPossible = hardEntityCollisionPossible;
        this.entityPushes = entityPushes == null ? List.of() : List.copyOf(entityPushes);
        this.capturedAt = capturedAt;
    }

    public boolean complete() { return complete; }
    public double maxStep() { return maxStep; }
    /** Complete block AABBs, independent of separately bounded entity-push uncertainty. */
    public boolean blockGeometryComplete() { return blockGeometryComplete; }
    public boolean entityPushPossible() { return entityPushPossible; }
    /** Server-observed hard entity AABBs, whose client position may be interpolated. */
    public boolean hardEntityCollisionPossible() { return hardEntityCollisionPossible; }
    /** Maximum overlapping, collidable pushable count observed in the short contact window. */
    public int entityPushCount() { return entityPushCount; }
    public List<EntityPush> entityPushes() { return entityPushes; }
    public double entityPushX() { return entityPushes.stream().mapToDouble(EntityPush::x).sum(); }
    public double entityPushZ() { return entityPushes.stream().mapToDouble(EntityPush::z).sum(); }
    /** Per-axis horizontal residual allowance inspired by Grim's entity uncertainty box. */
    public double entityPushHorizontalAllowance() { return Math.min(0.24, entityPushCount * 0.08); }
    static double horizontalResidual(double dx, double dz, double perAxisAllowance) {
        double residualX = Math.max(0, Math.abs(dx) - perAxisAllowance);
        double residualZ = Math.max(0, Math.abs(dz) - perAxisAllowance);
        return Math.hypot(residualX, residualZ);
    }
    static double horizontalVectorAllowance(double perAxisAllowance) {
        return perAxisAllowance * Math.sqrt(2);
    }
    public long capturedAt() { return capturedAt; }

    /** Returns vanilla collision outcomes, including legal step-up and axis-order alternatives. */
    public List<Move> resolve(double startX, double startY, double startZ,
                              double dx, double dy, double dz, boolean onGround) {
        MoveBuffer moves = new MoveBuffer();
        int count = resolveInto(startX, startY, startZ, dx, dy, dz, onGround, moves);
        if (count == 0) return List.of();
        if (count == 1) return List.of(new Move(moves.x(0), moves.y(0), moves.z(0)));
        List<Move> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new Move(moves.x(i), moves.y(i), moves.z(i)));
        return List.copyOf(result);
    }

    /**
     * Writes collision outcomes into a caller-owned buffer. Reusing one buffer
     * across candidate predictions avoids per-branch List and Move allocations.
     */
    public int resolveInto(double startX, double startY, double startZ,
                           double dx, double dy, double dz, boolean onGround,
                           MoveBuffer output) {
        output.clear();
        if (!blockGeometryComplete || !finite(startX, startY, startZ, dx, dy, dz)
                || !covers(startX, startY, startZ, dx, dy, dz)) return 0;

        if (shapes.length == 0) {
            output.setSingle(dx, dy, dz);
            return 1;
        }

        double bodyMinX = startX - width * 0.5, bodyMaxX = startX + width * 0.5;
        double bodyMinY = startY, bodyMaxY = startY + height;
        double bodyMinZ = startZ - depth * 0.5, bodyMaxZ = startZ + depth * 0.5;
        CollisionScratch scratch = SCRATCH.get();
        int shapeCount = nearbyShapes(bodyMinX, bodyMaxX, bodyMinY, bodyMaxY,
                bodyMinZ, bodyMaxZ, dx, dy, dz, onGround || dy < 0 ? maxStep : 0, scratch);
        if (shapeCount == 0) {
            output.setSingle(dx, dy, dz);
            return 1;
        }

        int[] nearbyShapeIds = scratch.nearbyShapeIds;
        boolean zBeforeX = Math.abs(dx) < Math.abs(dz);
        clip(bodyMinX, bodyMaxX, bodyMinY, bodyMaxY, bodyMinZ, bodyMaxZ,
                dx, dy, dz, zBeforeX, nearbyShapeIds, shapeCount, scratch.clippedMove);
        double baseX = scratch.clippedMove[0];
        double baseY = scratch.clippedMove[1];
        double baseZ = scratch.clippedMove[2];
        double alternateX = baseX, alternateY = baseY, alternateZ = baseZ;
        boolean hasAlternateOrder = dx != 0 && dz != 0;
        if (hasAlternateOrder) {
            clip(bodyMinX, bodyMaxX, bodyMinY, bodyMaxY, bodyMinZ, bodyMaxZ,
                    dx, dy, dz, !zBeforeX, nearbyShapeIds, shapeCount, scratch.clippedMove);
            alternateX = scratch.clippedMove[0];
            alternateY = scratch.clippedMove[1];
            alternateZ = scratch.clippedMove[2];
        }

        boolean collidedY = baseY != dy;
        boolean descendingCollision = collidedY && dy < 0;
        boolean canStep = (onGround || descendingCollision) && maxStep > 0;
        boolean primaryHorizontalCollision = baseX != dx || baseZ != dz;
        boolean alternateHorizontalCollision = alternateX != dx || alternateZ != dz;
        if (!canStep || (!primaryHorizontalCollision
                && (!hasAlternateOrder || !alternateHorizontalCollision))) {
            output.addIfAbsent(baseX, baseY, baseZ);
            if (hasAlternateOrder) output.addIfAbsent(alternateX, alternateY, alternateZ);
            return output.size();
        }

        double stepMinY = descendingCollision ? bodyMinY + baseY : bodyMinY;
        double stepMaxY = descendingCollision ? bodyMaxY + baseY : bodyMaxY;
        scratch.stepHeightCount = 0;
        if (stepProfile == StepProfile.V1_21_PLUS) {
            scratch.ensureStepHeightCapacity(shapeCount * 2);
            for (int i = 0; i < shapeCount; i++) {
                Box shape = shapes[nearbyShapeIds[i]];
                addStepHeight(scratch, (float) (shape.minY() - stepMinY), baseY);
                addStepHeight(scratch, (float) (shape.maxY() - stepMinY), baseY);
            }
            Arrays.sort(scratch.stepHeights, 0, scratch.stepHeightCount);
        }
        // addStepHeight may grow this array, so obtain it only after collecting
        // candidates. Sorting or replaying the old reference can crash here.
        float[] stepHeights = scratch.stepHeights;

        appendAxisOrderOutcomes(output, bodyMinX, bodyMaxX, bodyMinY, bodyMaxY, bodyMinZ, bodyMaxZ,
                dx, dy, dz, baseX, baseY, baseZ, onGround, descendingCollision,
                zBeforeX, nearbyShapeIds, shapeCount, stepMinY, stepMaxY,
                stepHeights, scratch.stepHeightCount, scratch.clippedMove);
        if (hasAlternateOrder) {
            appendAxisOrderOutcomes(output, bodyMinX, bodyMaxX, bodyMinY, bodyMaxY, bodyMinZ, bodyMaxZ,
                    dx, dy, dz, alternateX, alternateY, alternateZ, onGround, descendingCollision,
                    !zBeforeX, nearbyShapeIds, shapeCount, stepMinY, stepMaxY,
                    stepHeights, scratch.stepHeightCount, scratch.clippedMove);
        }
        return output.size();
    }

    private void appendAxisOrderOutcomes(MoveBuffer output,
                                         double bodyMinX, double bodyMaxX,
                                         double bodyMinY, double bodyMaxY,
                                         double bodyMinZ, double bodyMaxZ,
                                         double dx, double dy, double dz,
                                         double baseX, double baseY, double baseZ,
                                         boolean onGround, boolean descendingCollision,
                                         boolean zBeforeX, int[] nearbyShapeIds, int shapeCount,
                                         double stepMinY, double stepMaxY,
                                         float[] stepHeights, int stepHeightCount,
                                         double[] clippedMove) {
        output.addIfAbsent(baseX, baseY, baseZ);
        boolean collidedX = baseX != dx;
        boolean collidedZ = baseZ != dz;
        if (!(onGround || descendingCollision) || !(collidedX || collidedZ) || maxStep <= 0) return;

        if (stepProfile != StepProfile.V1_21_PLUS) {
            appendLegacyStepOutcome(output, bodyMinX, bodyMaxX, bodyMinY, bodyMaxY,
                    bodyMinZ, bodyMaxZ, dx, dy, dz, baseX, baseY, baseZ,
                    zBeforeX, nearbyShapeIds, shapeCount, clippedMove);
            return;
        }

        double baseHorizontal = baseX * baseX + baseZ * baseZ;
        float previousHeight = Float.NaN;
        for (int i = 0; i < stepHeightCount; i++) {
            float stepHeight = stepHeights[i];
            if (stepHeight == previousHeight) continue;
            previousHeight = stepHeight;
            clip(bodyMinX, bodyMaxX, stepMinY, stepMaxY, bodyMinZ, bodyMaxZ,
                    dx, stepHeight, dz, zBeforeX, nearbyShapeIds, shapeCount, clippedMove);
            double steppedX = clippedMove[0], steppedY = clippedMove[1];
            double steppedZ = clippedMove[2];
            if (steppedX * steppedX + steppedZ * steppedZ <= baseHorizontal) continue;
            output.addIfAbsent(steppedX, steppedY + stepMinY - bodyMinY, steppedZ);
            // Modern vanilla clients choose the first sorted step height that
            // improves horizontal movement, then stop. Retaining later heights
            // would accept positions the client does not produce.
            break;
        }
    }

    /**
     * Grim mirrors the pre-1.21 vanilla step-up path: try the configured full
     * step, apply the 1.8 swept-box fix where present, then clip the downward
     * part (which changed again in 1.14).
     */
    private void appendLegacyStepOutcome(MoveBuffer output,
                                        double bodyMinX, double bodyMaxX,
                                        double bodyMinY, double bodyMaxY,
                                        double bodyMinZ, double bodyMaxZ,
                                        double dx, double dy, double dz,
                                        double baseX, double baseY, double baseZ,
                                        boolean zBeforeX, int[] nearbyShapeIds, int shapeCount,
                                        double[] clippedMove) {
        clip(bodyMinX, bodyMaxX, bodyMinY, bodyMaxY, bodyMinZ, bodyMaxZ,
                dx, maxStep, dz, zBeforeX, nearbyShapeIds, shapeCount, clippedMove);
        double stepX = clippedMove[0], stepY = clippedMove[1], stepZ = clippedMove[2];

        if (stepProfile != StepProfile.PRE_1_8) {
            double expandedMinX = dx < 0 ? bodyMinX + dx : bodyMinX;
            double expandedMaxX = dx > 0 ? bodyMaxX + dx : bodyMaxX;
            double expandedMinZ = dz < 0 ? bodyMinZ + dz : bodyMinZ;
            double expandedMaxZ = dz > 0 ? bodyMaxZ + dz : bodyMaxZ;
            double bugFixStepY = clipAxis(expandedMinX, expandedMaxX,
                    bodyMinY, bodyMaxY, expandedMinZ, expandedMaxZ,
                    1, maxStep, nearbyShapeIds, shapeCount);
            if (bugFixStepY < maxStep) {
                clip(bodyMinX, bodyMaxX, bodyMinY + bugFixStepY, bodyMaxY + bugFixStepY,
                        bodyMinZ, bodyMaxZ, dx, 0, dz, zBeforeX,
                        nearbyShapeIds, shapeCount, clippedMove);
                double bugFixX = clippedMove[0], bugFixZ = clippedMove[2];
                if (bugFixX * bugFixX + bugFixZ * bugFixZ > stepX * stepX + stepZ * stepZ) {
                    stepX = bugFixX;
                    stepY = bugFixStepY;
                    stepZ = bugFixZ;
                }
            }
        }

        if (stepX * stepX + stepZ * stepZ <= baseX * baseX + baseZ * baseZ) return;
        double down = -stepY;
        if (stepProfile == StepProfile.V1_14_TO_1_20) down += dy;
        double resolvedDown = clipAxis(bodyMinX + stepX, bodyMaxX + stepX,
                bodyMinY + stepY, bodyMaxY + stepY,
                bodyMinZ + stepZ, bodyMaxZ + stepZ,
                1, down, nearbyShapeIds, shapeCount);
        output.addIfAbsent(stepX, stepY + resolvedDown, stepZ);
    }

    private void addStepHeight(CollisionScratch scratch, float candidate, double collidedY) {
        // Vanilla's step height is a float. A configured max step of 0.6f is
        // therefore slightly above the double literal 0.6; tolerate that
        // conversion boundary while still rejecting genuinely excessive steps.
        if (candidate < 0 || candidate > maxStep + 1.0E-6 || candidate == (float) collidedY) return;
        scratch.ensureStepHeightCapacity(scratch.stepHeightCount + 1);
        scratch.stepHeights[scratch.stepHeightCount++] = candidate;
    }

    private boolean covers(double x, double y, double z, double dx, double dy, double dz) {
        double sweptMinX = Math.min(x, x + dx) - width * 0.5;
        double sweptMaxX = Math.max(x, x + dx) + width * 0.5;
        double sweptMinY = Math.min(y, y + dy);
        double sweptMaxY = Math.max(y, y + dy) + height + maxStep;
        double sweptMinZ = Math.min(z, z + dz) - depth * 0.5;
        double sweptMaxZ = Math.max(z, z + dz) + depth * 0.5;
        return sweptMinX >= minX && sweptMaxX <= maxX
                && sweptMinY >= minY && sweptMaxY <= maxY
                && sweptMinZ >= minZ && sweptMaxZ <= maxZ;
    }

    private int nearbyShapes(double bodyMinX, double bodyMaxX, double bodyMinY, double bodyMaxY,
                             double bodyMinZ, double bodyMaxZ,
                             double dx, double dy, double dz, double extraUp,
                             CollisionScratch scratch) {
        int minX = floor(Math.min(bodyMinX, bodyMinX + dx));
        int maxX = floor(Math.max(bodyMaxX, bodyMaxX + dx));
        int minY = floor(Math.min(bodyMinY, bodyMinY + dy));
        int maxY = floor(Math.max(bodyMaxY, bodyMaxY + dy) + extraUp);
        int minZ = floor(Math.min(bodyMinZ, bodyMinZ + dz));
        int maxZ = floor(Math.max(bodyMaxZ, bodyMaxZ + dz));
        scratch.beginVisit(shapes.length);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int cell = cellIndex(x, y, z, minCellX, minCellY,
                            minCellZ, cellsY, cellsZ);
                    for (int i = cellShapeOffsets[cell]; i < cellShapeOffsets[cell + 1]; i++)
                        scratch.addShape(cellShapeIds[i]);
                }
            }
        }
        return scratch.nearbyCount;
    }

    private void clip(double minX, double maxX, double minY, double maxY, double minZ, double maxZ,
                      double dx, double dy, double dz, boolean zBeforeX,
                      int[] shapeIds, int shapeCount, double[] result) {
        double movedY = clipAxis(minX, maxX, minY, maxY, minZ, maxZ, 1, dy, shapeIds, shapeCount);
        minY += movedY;
        maxY += movedY;
        double movedX, movedZ;
        if (zBeforeX) {
            movedZ = clipAxis(minX, maxX, minY, maxY, minZ, maxZ, 2, dz, shapeIds, shapeCount);
            minZ += movedZ;
            maxZ += movedZ;
            movedX = clipAxis(minX, maxX, minY, maxY, minZ, maxZ, 0, dx, shapeIds, shapeCount);
        } else {
            movedX = clipAxis(minX, maxX, minY, maxY, minZ, maxZ, 0, dx, shapeIds, shapeCount);
            minX += movedX;
            maxX += movedX;
            movedZ = clipAxis(minX, maxX, minY, maxY, minZ, maxZ, 2, dz, shapeIds, shapeCount);
        }
        result[0] = movedX;
        result[1] = movedY;
        result[2] = movedZ;
    }

    private double clipAxis(double minX, double maxX, double minY, double maxY,
                            double minZ, double maxZ, int axis, double wanted,
                            int[] shapeIds, int shapeCount) {
        double clipped = wanted;
        for (int i = 0; i < shapeCount; i++) {
            Box shape = shapes[shapeIds[i]];
            if ((axis != 1 && !(maxY > shape.minY() && minY < shape.maxY()))
                    || (axis != 0 && !(maxX > shape.minX() && minX < shape.maxX()))
                    || (axis != 2 && !(maxZ > shape.minZ() && minZ < shape.maxZ()))) continue;
            double bodyMin = axis == 0 ? minX : axis == 1 ? minY : minZ;
            double bodyMax = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
            double shapeMin = min(shape, axis), shapeMax = max(shape, axis);
            if (wanted > 0 && bodyMax <= shapeMin)
                clipped = Math.min(clipped, shapeMin - bodyMax);
            else if (wanted < 0 && bodyMin >= shapeMax)
                clipped = Math.max(clipped, shapeMax - bodyMin);
        }
        return clipped;
    }

    private static int cellIndex(int x, int y, int z, int minX, int minY,
                                 int minZ, int cellsY, int cellsZ) {
        return ((x - minX) * cellsY + y - minY) * cellsZ + z - minZ;
    }

    private static double min(Box box, int axis) {
        return axis == 0 ? box.minX() : axis == 1 ? box.minY() : box.minZ();
    }

    private static double max(Box box, int axis) {
        return axis == 0 ? box.maxX() : axis == 1 ? box.maxY() : box.maxZ();
    }

    private static int floor(double value) { return (int) Math.floor(value); }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
