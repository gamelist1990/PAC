package org.pexserver.pac.movement;

import org.pexserver.pac.packet.JavaInputCapture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Replays short runs of Java physics steps when coordinates were omitted or batched. */
public final class MultiStepMotionPredictor {
    public record Frame(float yaw, List<MotionPredictor.Input> inputs,
                        MotionEnvironment.Snapshot environment, boolean jumpPossible,
                        MotionCollisionSnapshot collisions) {
        public Frame(float yaw, List<MotionPredictor.Input> inputs,
                     MotionEnvironment.Snapshot environment, boolean jumpPossible) {
            this(yaw, inputs, environment, jumpPossible, null);
        }
    }
    public record Result(double offset, MotionPredictor.Motion finalVelocity) { }
    public record VerticalResult(double offset, double finalVelocity) { }

    private record Path(double x, double y, double z, MotionPredictor.Motion velocity) { }
    private record CandidateReplay(Path path, boolean unambiguous) { }
    private record RankedPath(Path path, double score, long order) { }
    private static final Comparator<RankedPath> BEST_PATH_FIRST = Comparator
            .comparingDouble(RankedPath::score).thenComparingLong(RankedPath::order);
    private static final MotionPredictor.Motion ZERO = new MotionPredictor.Motion(0, 0, 0);
    private static final List<MotionPredictor.Input> UNOBSERVED_INPUTS = unobservedInputs();
    private static final List<List<MotionPredictor.Input>> OBSERVED_SINGLE_INPUTS = observedSingleInputs();
    private static final int MAX_BEAM = 2_048;
    private static final int MAX_FRAMES = 40;
    private static final double GRIM_NEAR_ZERO_OFFSET = 1.0E-5;

    private MultiStepMotionPredictor() { }

    public static Frame frame(float yaw, JavaInputCapture.Window input,
                              MotionEnvironment.Snapshot environment) {
        return frame(yaw, input, environment, null);
    }

    public static Frame frame(float yaw, JavaInputCapture.Window input,
                              MotionEnvironment.Snapshot environment,
                              MotionCollisionSnapshot collisions) {
        List<MotionPredictor.Input> candidates = inputCandidates(input);
        boolean jumpPossible = input == null || input.current() == null;
        if (input != null && input.current() != null) {
            jumpPossible = input.current().jump()
                    || input.previous() != null && input.previous().jump();
        }
        float heading = Float.isFinite(yaw) ? yaw : environment == null ? 0 : environment.yaw();
        return new Frame(heading, candidates, environment, jumpPossible, collisions);
    }

    /** Returns the shared movement-input candidates without constructing a simulation frame. */
    static List<MotionPredictor.Input> inputCandidates(JavaInputCapture.Window input) {
        if (input == null || input.current() == null) return UNOBSERVED_INPUTS;
        MotionPredictor.Input current = input.current();
        MotionPredictor.Input previous = input.previous();
        if (previous != null && !previous.equals(current)) return List.of(current, previous);
        return OBSERVED_SINGLE_INPUTS.get(inputMask(current));
    }

    private static List<MotionPredictor.Input> unobservedInputs() {
        List<MotionPredictor.Input> candidates = new ArrayList<>(9);
        for (int forward = -1; forward <= 1; forward++) {
            for (int strafe = -1; strafe <= 1; strafe++) {
                candidates.add(new MotionPredictor.Input(forward > 0, forward < 0,
                        strafe > 0, strafe < 0, false, false, false));
            }
        }
        return List.copyOf(candidates);
    }

    private static List<List<MotionPredictor.Input>> observedSingleInputs() {
        List<List<MotionPredictor.Input>> candidates = new ArrayList<>(128);
        for (int mask = 0; mask < 128; mask++) {
            candidates.add(List.of(new MotionPredictor.Input(
                    (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0,
                    (mask & 16) != 0, (mask & 32) != 0, (mask & 64) != 0)));
        }
        return List.copyOf(candidates);
    }

    private static int inputMask(MotionPredictor.Input input) {
        return (input.forward() ? 1 : 0) | (input.backward() ? 2 : 0)
                | (input.left() ? 4 : 0) | (input.right() ? 8 : 0)
                | (input.jump() ? 16 : 0) | (input.shift() ? 32 : 0)
                | (input.sprint() ? 64 : 0);
    }

    public static List<Frame> lastFrames(List<Frame> observed, int count) {
        if (observed == null || count < 1 || observed.size() < count) return null;
        return List.copyOf(observed.subList(observed.size() - count, observed.size()));
    }

    /**
     * Estimates client physics steps across delayed position packets. Wall time
     * sets the horizon; observed look-only packets can add at most two frames
     * to account for a short network burst, so packet spam cannot create an
     * unbounded movement allowance.
     */
    public static int estimateFrames(long elapsedMillis, int positionlessPackets) {
        long elapsedTicks = Math.max(0, Math.round(Math.max(0, elapsedMillis) / 50.0));
        int timeFrames = (int) Math.max(1, Math.min(MAX_FRAMES, elapsedTicks));
        int observedFrames = (int) Math.max(1, Math.min(MAX_FRAMES, (long) positionlessPackets + 1));
        int burstLimit = Math.min(MAX_FRAMES, timeFrames + 2);
        return Math.max(timeFrames, Math.min(observedFrames, burstLimit));
    }

    public static Result ground(MotionPredictor.Motion initialVelocity,
                                double actualX, double actualZ, List<Frame> frames) {
        return ground(initialVelocity, actualX, actualZ, frames, 0, 0, 0);
    }

    public static Result ground(MotionPredictor.Motion initialVelocity,
                                double actualX, double actualZ, List<Frame> frames,
                                double startX, double startY, double startZ) {
        return simulate(initialVelocity, actualX, Double.NaN, actualZ, frames,
                true, startX, startY, startZ, false);
    }

    /** Ground replay that scores the complete collision-resolved X/Y/Z displacement. */
    public static Result ground3d(MotionPredictor.Motion initialVelocity,
                                  double actualX, double actualY, double actualZ,
                                  List<Frame> frames,
                                  double startX, double startY, double startZ) {
        if (frames == null || frames.isEmpty()) return null;
        for (Frame frame : frames)
            if (frame.collisions() == null || !frame.collisions().complete()) return null;
        return simulate(initialVelocity, actualX, actualY, actualZ, frames,
                true, startX, startY, startZ, false);
    }

    /** One-frame damage response check: block AABBs are exact; nearby entity pushes are separately tolerated. */
    public static Result groundImpulseResponse(MotionPredictor.Motion initialVelocity,
                                               double actualX, double actualZ, List<Frame> frames,
                                               double startX, double startY, double startZ) {
        Result best = simulate(initialVelocity, actualX, Double.NaN, actualZ, frames,
                true, startX, startY, startZ, true);
        if (frames == null || frames.size() != 1) return best;
        Frame frame = frames.get(0);
        MotionEnvironment.Snapshot environment = frame.environment();
        if (!frame.jumpPossible() || environment == null || !environment.ordinaryGround()
                || frame.collisions() != null && !frame.collisions().blockGeometryComplete()) return best;

        // A jump on the impact tick leaves the grounded travel path:
        // horizontal knockback then uses air drag and air input acceleration.
        // Evaluate that vanilla branch alongside the grounded branch instead
        // of interpreting a jump reset as suppressed knockback.
        float sneakScale = environment.sneaking() ? environment.sneakingSpeed() : 1.0f;
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        for (MotionPredictor.Input input : frame.inputs()) {
            MotionPredictor.Motion velocity = MotionPredictor.predictAirInputClient(
                    initialVelocity, ZERO, frame.yaw(), environment.sprinting(),
                    environment.horizontalDrag(), sneakScale, environment.itemUseMultiplier(), input)
                    .closest();
            int moveCount;
            if (frame.collisions() == null) {
                moves.setSingle(velocity.dx(), environment.jumpStrength(), velocity.dz());
                moveCount = 1;
            } else {
                moveCount = frame.collisions().resolveInto(startX, startY, startZ, velocity.dx(),
                        environment.jumpStrength(), velocity.dz(), false, moves);
                if (moveCount == 0) {
                    moves.setSingle(velocity.dx(), environment.jumpStrength(), velocity.dz());
                    moveCount = 1;
                }
            }
            for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                double moveX = moves.x(moveIndex), moveY = moves.y(moveIndex), moveZ = moves.z(moveIndex);
                double offset = Math.hypot(actualX - moveX, actualZ - moveZ);
                if (best == null || offset < best.offset()) best = new Result(offset,
                        postBlockSpeed(new MotionPredictor.Motion(moveX, moveY, moveZ),
                                environment, true));
            }
        }
        return best;
    }

    public static Result air(MotionPredictor.Motion initialVelocity,
                             double actualX, double actualZ, List<Frame> frames) {
        return air(initialVelocity, actualX, actualZ, frames, 0, 0, 0);
    }

    public static Result air(MotionPredictor.Motion initialVelocity,
                             double actualX, double actualZ, List<Frame> frames,
                             double startX, double startY, double startZ) {
        return simulate(initialVelocity, actualX, Double.NaN, actualZ, frames,
                false, startX, startY, startZ, false);
    }

    public static Result airImpulseResponse(MotionPredictor.Motion initialVelocity,
                                           double actualX, double actualZ, List<Frame> frames,
                                           double startX, double startY, double startZ) {
        return simulate(initialVelocity, actualX, Double.NaN, actualZ, frames,
                false, startX, startY, startZ, true);
    }

    public static VerticalResult airVertical(double previousDy, double actualDy,
                                             List<Frame> frames) {
        return airVertical(previousDy, actualDy, frames, Double.NaN, Double.NaN, Double.NaN);
    }

    public static VerticalResult airVertical(double previousDy, double actualDy,
                                             List<Frame> frames,
                                             double startX, double startY, double startZ) {
        return airVertical(previousDy, actualDy, frames, startX, startY, startZ, false);
    }

    public static VerticalResult airVertical(double previousDy, double actualDy,
                                             List<Frame> frames,
                                             double startX, double startY, double startZ,
                                             boolean allowUnknownEntityPush) {
        if (frames == null || frames.isEmpty() || frames.size() > MAX_FRAMES
                || !Double.isFinite(actualDy)) return null;
        for (Frame frame : frames) {
            MotionEnvironment.Snapshot environment = frame.environment();
            if (environment == null || !(environment.verticalAir() || environment.ordinaryGround())) return null;
            if (frame.collisions() != null && !(allowUnknownEntityPush
                    ? frame.collisions().blockGeometryComplete() : frame.collisions().complete())) return null;
        }
        VerticalResult best = verticalPath(previousDy, actualDy, frames, -1, startX, startY, startZ);
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame.jumpPossible() && frame.environment().ordinaryGround()) {
                VerticalResult candidate = verticalPath(previousDy, actualDy, frames, i,
                        startX, startY, startZ);
                if (candidate.offset() < best.offset()) best = candidate;
            }
        }
        return best;
    }

    private static VerticalResult verticalPath(double initialVelocity, double actualDy,
                                               List<Frame> frames, int jumpFrame,
                                               double startX, double startY, double startZ) {
        double velocity = initialVelocity;
        double total = 0;
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        for (int i = 0; i < frames.size(); i++) {
            MotionEnvironment.Snapshot environment = frames.get(i).environment();
            double desired;
            if (i == jumpFrame) desired = velocity = environment.jumpStrength();
            else if (environment.ordinaryGround()) desired = velocity = 0;
            else desired = velocity = AirPredictor.nextDisplacement(velocity, environment.gravity(),
                        environment.verticalDrag(), environment.slowFalling(),
                        environment.levitationAmplifier());
            double x = Double.isFinite(startX) ? startX : environment.x();
            double y = Double.isFinite(startY) ? startY + total : environment.y() + total;
            double z = Double.isFinite(startZ) ? startZ : environment.z();
            MotionCollisionSnapshot collision = frames.get(i).collisions();
            int moveCount;
            if (collision == null) {
                moves.setSingle(0, desired, 0);
                moveCount = 1;
            } else {
                moveCount = collision.resolveInto(x, y, z, 0, desired, 0,
                        environment.ordinaryGround(), moves);
                if (moveCount == 0) {
                    moves.setSingle(0, desired, 0);
                    moveCount = 1;
                }
            }
            double movedY = moves.y(0);
            for (int moveIndex = 1; moveIndex < moveCount; moveIndex++)
                movedY = Math.min(movedY, moves.y(moveIndex));
            if (Math.abs(desired - movedY) > 1.0E-9) velocity = 0;
            total += movedY;
        }
        return new VerticalResult(Math.abs(actualDy - total), velocity);
    }

    private static Result simulate(MotionPredictor.Motion initialVelocity,
                                   double actualX, double actualY, double actualZ,
                                   List<Frame> frames, boolean ground,
                                   double startX, double startY, double startZ,
                                   boolean allowUnknownEntityPush) {
        if (frames == null || frames.isEmpty() || frames.size() > MAX_FRAMES
                || !Double.isFinite(actualX) || !Double.isFinite(actualZ)) return null;
        double entityPushAllowance = 0;
        boolean futureCollisionBoundsTrusted = true;
        for (Frame frame : frames) {
            MotionEnvironment.Snapshot environment = frame.environment();
            if (environment == null || !(ground ? environment.ordinaryGround() : airSimulationSupported(frame)))
                return null;
            MotionCollisionSnapshot collision = frame.collisions();
            if (collision != null) {
                if (!(allowUnknownEntityPush
                        ? collision.blockGeometryComplete() : collision.complete())) return null;
                entityPushAllowance = Math.max(entityPushAllowance, collision.entityPushHorizontalAllowance());
            }
            futureCollisionBoundsTrusted &= collision != null && collision.blockGeometryComplete()
                    && (!ground || environment.blockSpeedFactor() <= 1.0f);
        }

        int beamLimit = MAX_BEAM;
        boolean scoreVertical = Double.isFinite(actualY);
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        CandidateReplay replay = firstFeasiblePath(initialVelocity, frames, ground,
                actualX, actualY, actualZ, scoreVertical, startX, startY, startZ, moves);
        Path best = replay == null ? null : replay.path();
        double bestOffset = best == null ? Double.POSITIVE_INFINITY
                : displacementOffset(actualX, actualY, actualZ,
                        best.x(), best.y(), best.z(), scoreVertical, entityPushAllowance);
        // Grim stops candidate iteration once a legal path is effectively exact.
        // Keep impulse-response searches exhaustive because callers use their
        // residual to judge knockback suppression, not only movement legality.
        if (best != null && (replay.unambiguous()
                || !allowUnknownEntityPush && bestOffset < GRIM_NEAR_ZERO_OFFSET))
            return new Result(bestOffset, best.velocity());
        List<Path> beam = List.of(new Path(0, 0, 0, initialVelocity));
        double[] futureMaximumAcceleration = futureAccelerationBounds(frames, ground, scoreVertical);
        for (int index = 0; index < frames.size(); index++) {
            Frame frame = frames.get(index);
            MotionEnvironment.Snapshot environment = frame.environment();
            int remaining = frames.size() - index - 1;
            // Keep the same stable top-K beam as a full sort, without retaining
            // every expanded path when only MAX_BEAM paths can survive.
            PriorityQueue<RankedPath> expanded = remaining == 0 ? null
                    : new PriorityQueue<>(Math.max(1,
                            Math.min(beamLimit, beam.size() * frame.inputs().size())),
                            BEST_PATH_FIRST.reversed());
            Set<Path> retainedPaths = remaining == 0 ? null
                    : new HashSet<>(initialRetainedPathCapacity(beam.size(), frame.inputs().size(), beamLimit));
            long expansionOrder = 0;
            for (Path path : beam) {
                for (MotionPredictor.Input input : frame.inputs()) {
                    MotionPredictor.Motion velocity = predictInputMotion(path.velocity(), frame, input, ground);
                    MotionCollisionSnapshot collision = frame.collisions();
                    if (best != null && collision != null && futureCollisionBoundsTrusted) {
                        double distanceBound = collisionResultLowerBound(
                                actualX - path.x(), Double.isFinite(actualY) ? actualY - path.y() : 0,
                                actualZ - path.z(), velocity.dx(), velocity.dy(), velocity.dz(),
                                collision.maxStep(), ground, scoreVertical, entityPushAllowance);
                        double lowerBound = distanceBound;
                        if (remaining > 0) {
                            double speed = Math.hypot(velocity.dx(), velocity.dz());
                            double reach = remaining * speed
                                    + futureMaximumAcceleration[index + 1] * remaining * (remaining + 1) * 0.5;
                            lowerBound = Math.max(0, distanceBound - reach);
                        }
                        // Every block-clipped movement lies inside this component-wise
                        // bound. Subtract the maximum future travel for intermediate
                        // frames; skip collision enumeration only if no completion can
                        // beat the known exact feasible path.
                        if (lowerBound > bestOffset + 1.0e-12) continue;
                    }
                    int moveCount;
                    if (collision == null) {
                        moves.setSingle(velocity.dx(), velocity.dy(), velocity.dz());
                        moveCount = 1;
                    } else {
                        moveCount = collision.resolveInto(startX + path.x(), startY + path.y(),
                                startZ + path.z(), velocity.dx(), velocity.dy(), velocity.dz(), ground, moves);
                    }
                    // Out-of-window candidate positions are conservatively treated as unobstructed.
                    if (moveCount == 0) {
                        moves.setSingle(velocity.dx(), velocity.dy(), velocity.dz());
                        moveCount = 1;
                    }
                    for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                        double moveX = moves.x(moveIndex), moveY = moves.y(moveIndex), moveZ = moves.z(moveIndex);
                        double nextX = path.x() + moveX;
                        double nextY = path.y() + moveY;
                        double nextZ = path.z() + moveZ;
                        if (remaining == 0) {
                            double offset = displacementOffset(actualX, actualY, actualZ,
                                    nextX, nextY, nextZ, scoreVertical, entityPushAllowance);
                            if (offset < bestOffset) {
                                bestOffset = offset;
                                best = new Path(nextX, nextY, nextZ, postBlockSpeed(
                                        new MotionPredictor.Motion(
                                                moveX == velocity.dx() ? velocity.dx() : 0,
                                                moveY == velocity.dy() ? velocity.dy() : 0,
                                                moveZ == velocity.dz() ? velocity.dz() : 0),
                                        environment, ground));
                            }
                        } else {
                            double distance = displacementOffset(actualX, actualY, actualZ,
                                    nextX, nextY, nextZ, scoreVertical, entityPushAllowance);
                            double nextDx = moveX == velocity.dx() ? velocity.dx() : 0;
                            double nextDz = moveZ == velocity.dz() ? velocity.dz() : 0;
                            double speed = Math.hypot(nextDx, nextDz);
                            double reach = remaining * speed
                                    + futureMaximumAcceleration[index + 1] * remaining * (remaining + 1) * 0.5;
                            double lowerBound = Math.max(0, distance - reach);
                            double score = lowerBound + distance * 1.0e-6;
                            long order = expansionOrder++;
                            RankedPath worst = expanded.peek();
                            int comparedToWorst = worst == null ? -1 : Double.compare(score, worst.score());
                            if (comparedToWorst == 0 && worst != null)
                                comparedToWorst = Long.compare(order, worst.order());
                            if (expanded.size() < beamLimit || worst != null && comparedToWorst < 0) {
                                MotionPredictor.Motion nextVelocity = postBlockSpeed(
                                        new MotionPredictor.Motion(nextDx,
                                                moveY == velocity.dy() ? velocity.dy() : 0, nextDz),
                                        environment, ground);
                                Path next = new Path(nextX, nextY, nextZ, nextVelocity);
                                // Different inputs can collapse to the exact same state at a wall.
                                // Keep one copy so duplicates cannot consume the whole search beam.
                                if (retainedPaths.add(next)) {
                                    RankedPath candidate = new RankedPath(next, score, order);
                                    if (expanded.size() == beamLimit) {
                                        RankedPath removed = expanded.poll();
                                        retainedPaths.remove(removed.path());
                                    }
                                    expanded.add(candidate);
                                }
                            }
                        }
                    }
                }
            }
            if (remaining > 0) {
                List<RankedPath> bestPaths = new ArrayList<>(expanded);
                bestPaths.sort(BEST_PATH_FIRST);
                int count = bestPaths.size();
                List<Path> nextBeam = new ArrayList<>(count);
                for (int i = 0; i < count; i++) nextBeam.add(bestPaths.get(i).path());
                beam = nextBeam;
            }
        }
        return best == null ? null : new Result(bestOffset, best.velocity());
    }

    private static int initialRetainedPathCapacity(int beamSize, int inputCount, int beamLimit) {
        long expectedPaths = Math.max(1L, Math.min((long) beamLimit + 1,
                (long) beamSize * inputCount));
        // HashSet takes a table capacity; pre-size for its 0.75 load factor.
        // Most observed frames have one or two inputs, so reserving the full
        // beam for each frame creates large short-lived arrays unnecessarily.
        return (int) Math.max(16, (expectedPaths * 4 + 2) / 3);
    }

    private static boolean airSimulationSupported(Frame frame) {
        MotionEnvironment.Snapshot environment = frame.environment();
        if (environment.ordinaryAir()) return true;
        MotionCollisionSnapshot collisions = frame.collisions();
        return environment.verticalAir() && collisions != null && collisions.blockGeometryComplete();
    }

    /** Finds a feasible path toward the observed endpoint to seed and bound the beam. */
    private static CandidateReplay firstFeasiblePath(MotionPredictor.Motion initialVelocity,
                                                    List<Frame> frames, boolean ground,
                                                    double actualX, double actualY, double actualZ,
                                                    boolean scoreVertical,
                                                    double startX, double startY, double startZ,
                                                    MotionCollisionSnapshot.MoveBuffer moves) {
        double x = 0, y = 0, z = 0;
        MotionPredictor.Motion velocity = initialVelocity;
        boolean unambiguous = true;
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            Frame frame = frames.get(frameIndex);
            if (frame.inputs().isEmpty()) return null;
            if (frame.inputs().size() != 1) unambiguous = false;
            MotionCollisionSnapshot collision = frame.collisions();
            if (collision != null && collision.entityPushPossible()) unambiguous = false;

            double remainingFrames = frames.size() - frameIndex;
            double targetX = (actualX - x) / remainingFrames;
            double targetY = scoreVertical ? (actualY - y) / remainingFrames : 0;
            double targetZ = (actualZ - z) / remainingFrames;
            double bestWaypointDistance = Double.POSITIVE_INFINITY;
            MotionPredictor.Motion chosenWanted = null;
            for (MotionPredictor.Input input : frame.inputs()) {
                MotionPredictor.Motion wanted = predictInputMotion(velocity, frame, input, ground);
                double dx = wanted.dx() - targetX, dy = scoreVertical ? wanted.dy() - targetY : 0,
                        dz = wanted.dz() - targetZ;
                double waypointDistance = dx * dx + dy * dy + dz * dz;
                if (waypointDistance < bestWaypointDistance) {
                    bestWaypointDistance = waypointDistance;
                    chosenWanted = wanted;
                }
            }

            if (chosenWanted == null) return null;
            int moveCount;
            if (collision == null) {
                moves.setSingle(chosenWanted.dx(), chosenWanted.dy(), chosenWanted.dz());
                moveCount = 1;
            } else {
                moveCount = collision.resolveInto(startX + x, startY + y, startZ + z,
                        chosenWanted.dx(), chosenWanted.dy(), chosenWanted.dz(), ground, moves);
            }
            if (moveCount == 0) {
                moves.setSingle(chosenWanted.dx(), chosenWanted.dy(), chosenWanted.dz());
                moveCount = 1;
                unambiguous = false;
            }
            if (moveCount != 1) unambiguous = false;

            double chosenX = 0, chosenY = 0, chosenZ = 0;
            double chosenCollisionDistance = Double.POSITIVE_INFINITY;
            for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                double moveX = moves.x(moveIndex), moveY = moves.y(moveIndex), moveZ = moves.z(moveIndex);
                double dx = moveX - targetX, dy = scoreVertical ? moveY - targetY : 0,
                        dz = moveZ - targetZ;
                double waypointDistance = dx * dx + dy * dy + dz * dz;
                if (waypointDistance >= chosenCollisionDistance) continue;
                chosenCollisionDistance = waypointDistance;
                chosenX = moveX;
                chosenY = moveY;
                chosenZ = moveZ;
            }
            x += chosenX;
            y += chosenY;
            z += chosenZ;
            velocity = postBlockSpeed(new MotionPredictor.Motion(
                    chosenX == chosenWanted.dx() ? chosenWanted.dx() : 0,
                    chosenY == chosenWanted.dy() ? chosenWanted.dy() : 0,
                    chosenZ == chosenWanted.dz() ? chosenWanted.dz() : 0),
                    frame.environment(), ground);
        }
        return new CandidateReplay(new Path(x, y, z, velocity), unambiguous);
    }

    private static MotionPredictor.Motion postBlockSpeed(MotionPredictor.Motion motion,
                                                          MotionEnvironment.Snapshot environment,
                                                          boolean ground) {
        if (!ground || motion == null || environment == null) return motion;
        float factor = environment.blockSpeedFactor();
        if (!Float.isFinite(factor) || factor < 0 || factor > 4) factor = 1.0f;
        return new MotionPredictor.Motion(motion.dx() * factor, motion.dy(), motion.dz() * factor);
    }

    private static MotionPredictor.Motion predictInputMotion(MotionPredictor.Motion previous, Frame frame,
                                                             MotionPredictor.Input input, boolean ground) {
        MotionEnvironment.Snapshot environment = frame.environment();
        float sneakScale = environment.sneaking() ? environment.sneakingSpeed() : 1.0f;
        MotionPredictor.Motion horizontal = ground
                ? MotionPredictor.predictGroundInputClient(previous, ZERO, frame.yaw(),
                        environment.movementSpeed(), environment.groundFriction(),
                        environment.horizontalDrag(), sneakScale,
                        environment.itemUseMultiplier(), input).closest()
                : MotionPredictor.predictAirInputClient(previous, ZERO, frame.yaw(),
                        environment.sprinting(), environment.horizontalDrag(), sneakScale,
                        environment.itemUseMultiplier(), input).closest();
        double vertical = ground ? 0 : AirPredictor.nextDisplacement(previous.dy(),
                environment.gravity(), environment.verticalDrag(), environment.slowFalling(),
                environment.levitationAmplifier());
        return new MotionPredictor.Motion(horizontal.dx(), vertical, horizontal.dz());
    }

    private static double[] futureAccelerationBounds(List<Frame> frames, boolean ground,
                                                     boolean includeStepHeight) {
        double[] result = new double[frames.size()];
        for (int i = frames.size() - 2; i >= 0; i--) {
            MotionEnvironment.Snapshot environment = frames.get(i + 1).environment();
            double acceleration;
            if (ground) {
                float sneakScale = environment.sneaking() ? environment.sneakingSpeed() : 1.0f;
                acceleration = MotionPredictor.maximumGroundStepClient(ZERO,
                        environment.movementSpeed(), environment.groundFriction(),
                        environment.horizontalDrag(), sneakScale, environment.itemUseMultiplier());
            } else {
                float sneakScale = environment.sneaking() ? environment.sneakingSpeed() : 1.0f;
                acceleration = MotionPredictor.maximumAirTravelClient(ZERO,
                        environment.sprinting(), environment.horizontalDrag(),
                        sneakScale, environment.itemUseMultiplier(), 1);
            }
            if (ground && includeStepHeight && frames.get(i + 1).collisions() != null)
                acceleration = Math.hypot(acceleration, frames.get(i + 1).collisions().maxStep());
            result[i] = Math.max(result[i + 1], acceleration);
        }
        if (frames.size() > 1) {
            MotionEnvironment.Snapshot last = frames.get(frames.size() - 1).environment();
            float sneakScale = last.sneaking() ? last.sneakingSpeed() : 1.0f;
            double acceleration = ground
                    ? MotionPredictor.maximumGroundStepClient(ZERO, last.movementSpeed(),
                            last.groundFriction(), last.horizontalDrag(), sneakScale,
                            last.itemUseMultiplier())
                    : MotionPredictor.maximumAirTravelClient(ZERO, last.sprinting(),
                            last.horizontalDrag(), sneakScale, last.itemUseMultiplier(), 1);
            if (ground && includeStepHeight && frames.getLast().collisions() != null)
                acceleration = Math.hypot(acceleration, frames.getLast().collisions().maxStep());
            result[frames.size() - 1] = Math.max(result[frames.size() - 1], acceleration);
        }
        return result;
    }

    private static double displacementOffset(double actualX, double actualY, double actualZ,
                                             double predictedX, double predictedY, double predictedZ,
                                             boolean includeY, double horizontalAllowance) {
        double horizontal = MotionCollisionSnapshot.horizontalResidual(
                actualX - predictedX, actualZ - predictedZ, horizontalAllowance);
        return includeY ? Math.hypot(horizontal, actualY - predictedY) : horizontal;
    }

    /** Admissible distance lower bound for every collision-clipped result of one input vector. */
    static double collisionResultLowerBound(double targetX, double targetY, double targetZ,
                                            double wantedX, double wantedY, double wantedZ,
                                            double maxStep, boolean onGround, boolean includeY) {
        return collisionResultLowerBound(targetX, targetY, targetZ, wantedX, wantedY, wantedZ,
                maxStep, onGround, includeY, 0);
    }

    static double collisionResultLowerBound(double targetX, double targetY, double targetZ,
                                            double wantedX, double wantedY, double wantedZ,
                                            double maxStep, boolean onGround, boolean includeY,
                                            double entityPushAllowance) {
        double dx = Math.max(0, distanceOutside(targetX,
                Math.min(0, wantedX), Math.max(0, wantedX)) - entityPushAllowance);
        double dz = Math.max(0, distanceOutside(targetZ,
                Math.min(0, wantedZ), Math.max(0, wantedZ)) - entityPushAllowance);
        double horizontal = Math.hypot(dx, dz);
        if (!includeY) return horizontal;
        double minY = Math.min(0, wantedY);
        double maxY = Math.max(0, wantedY);
        // The collision resolver also tries a step-up branch when a downward
        // request was clipped, even if the pre-tick ground bit was false.
        if (onGround || wantedY < 0) maxY = Math.max(maxY, maxStep);
        double dy = distanceOutside(targetY, minY, maxY);
        return Math.hypot(horizontal, dy);
    }

    private static double distanceOutside(double value, double min, double max) {
        return value < min ? min - value : value > max ? value - max : 0;
    }
}
