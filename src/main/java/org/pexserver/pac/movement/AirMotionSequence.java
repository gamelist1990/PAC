package org.pexserver.pac.movement;

import org.pexserver.pac.packet.JavaInputCapture;
import org.pexserver.pac.packet.ExternalMotionTracker;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/** Packet-order state for one player's clear-air movement prediction. */
public final class AirMotionSequence {
    private ServerTickTiming.Snapshot timing = ServerTickTiming.Snapshot.NORMAL;
    public void timing(ServerTickTiming.Snapshot timing) { this.timing = timing; }

    public record Sample(boolean evaluated, double offset, boolean abrupt,
                         double horizontalDistance, double verticalDistance,
                         boolean externalImpulseMismatch, boolean horizontalEvaluated, double horizontalOffset,
                         int skippedFrames) { }
    public record Position(double x, double y, double z) { }

    private double x, y, z, previousDy;
    private MotionPredictor.Motion previousMotion = new MotionPredictor.Motion(0, 0, 0);
    private final ExternalMotionWindow externalMotion = new ExternalMotionWindow();
    private final ArrayDeque<MultiStepMotionPredictor.Frame> positionlessInputs = new ArrayDeque<>();
    private long lastFrameAt;
    private int positionlessFrames;
    private int lastTick = -1;
    private boolean reliable, initialized;
    private boolean sprinting, sneaking, usingItem, packetYawKnown, slowFalling;
    private boolean positionlessYawTransition;
    private int levitationAmplifier = -1;
    private double gravity = 0.08;
    private float horizontalDrag = 0.91f, verticalDrag = 0.98f;
    private float sneakingSpeed = 0.3f, itemUseMultiplier = 1.0f;
    private float stuckHorizontalMultiplier = 1.0f, stuckVerticalMultiplier = 1.0f;
    private float yaw;

    public Position lastPosition() { return initialized ? new Position(x, y, z) : null; }
    public MotionPredictor.Motion motion() { return previousMotion; }
    public double verticalVelocity() { return previousDy; }

    /** Rebase after PAC rejects movement while keeping the server velocity baseline. */
    public void rebase(double x, double y, double z, double velocityX, double velocityY,
                       double velocityZ, MotionEnvironment.Snapshot environment, long now) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        this.x = x; this.y = y; this.z = z;
        previousMotion = new MotionPredictor.Motion(velocityX, velocityY, velocityZ);
        previousDy = velocityY;
        externalMotion.reset();
        positionlessInputs.clear();
        positionlessFrames = 0;
        positionlessYawTransition = false;
        lastFrameAt = now;
        lastTick = environment == null ? -1 : environment.tick();
        initialized = true;
        reliable = environment != null && environment.ordinaryAir();
        if (environment != null) {
            packetYawKnown = Float.isFinite(environment.yaw());
            yaw = environment.yaw();
            sprinting = environment.sprinting();
            sneaking = environment.sneaking();
            usingItem = environment.usingItem();
            gravity = environment.gravity();
            slowFalling = environment.slowFalling();
            levitationAmplifier = environment.levitationAmplifier();
            horizontalDrag = environment.horizontalDrag();
            verticalDrag = environment.verticalDrag();
            sneakingSpeed = environment.sneakingSpeed();
            itemUseMultiplier = environment.itemUseMultiplier();
            stuckHorizontalMultiplier = environment.stuckHorizontalMultiplier();
            stuckVerticalMultiplier = environment.stuckVerticalMultiplier();
        }
    }

    public Sample accept(boolean hasPosition, double packetX, double packetY, double packetZ,
                         MotionEnvironment.Snapshot environment, long now) {
        return accept(hasPosition, false, packetX, packetY, packetZ, 0, environment, now);
    }

    public Sample accept(boolean hasPosition, boolean hasRotation,
                         double packetX, double packetY, double packetZ, float packetYaw,
                         MotionEnvironment.Snapshot environment, long now) {
        return accept(hasPosition, hasRotation, packetX, packetY, packetZ, packetYaw,
                environment, now, null);
    }

    public Sample accept(boolean hasPosition, boolean hasRotation,
                         double packetX, double packetY, double packetZ, float packetYaw,
                         MotionEnvironment.Snapshot environment, long now,
                         JavaInputCapture.Window inputs) {
        return accept(hasPosition, hasRotation, packetX, packetY, packetZ, packetYaw,
                environment, now, inputs, null);
    }

    public Sample accept(boolean hasPosition, boolean hasRotation,
                         double packetX, double packetY, double packetZ, float packetYaw,
                         MotionEnvironment.Snapshot environment, long now,
                         JavaInputCapture.Window inputs, ExternalMotionTracker.Impulse impulse) {
        return accept(hasPosition, hasRotation, packetX, packetY, packetZ, packetYaw,
                environment, now, inputs, impulse, null);
    }

    public Sample accept(boolean hasPosition, boolean hasRotation,
                         double packetX, double packetY, double packetZ, float packetYaw,
                         MotionEnvironment.Snapshot environment, long now,
                         JavaInputCapture.Window inputs, ExternalMotionTracker.Impulse impulse,
                         MotionCollisionSnapshot collisions) {
        return accept(hasPosition, hasRotation, packetX, packetY, packetZ, packetYaw,
                environment, now, inputs, impulse, collisions, false);
    }

    public Sample accept(boolean hasPosition, boolean hasRotation,
                         double packetX, double packetY, double packetZ, float packetYaw,
                         MotionEnvironment.Snapshot environment, long now,
                         JavaInputCapture.Window inputs, ExternalMotionTracker.Impulse impulse,
                         MotionCollisionSnapshot collisions, boolean attackTransition) {
        float priorYaw = yaw;
        boolean priorYawKnown = packetYawKnown;
        if (hasRotation) {
            packetYawKnown = Float.isFinite(packetYaw);
            if (packetYawKnown) yaw = packetYaw;
        }
        // A combined move/look packet can describe the new camera heading even
        // when this movement step used the heading from the preceding packet.
        boolean yawChanged = hasRotation && priorYawKnown && packetYawKnown && priorYaw != yaw;
        float alternateHeading = yawChanged ? priorYaw : Float.NaN;
        // A rotation-only packet is not a zero-velocity physics step. The next
        // position packet may contain displacement accumulated since this one.
        if (!hasPosition) {
            if (yawChanged) positionlessYawTransition = true;
            positionlessInputs.addLast(MultiStepMotionPredictor.frame(
                    packetYawKnown ? yaw : environment == null ? Float.NaN : environment.yaw(),
                    inputs, environment, collisions));
            if (positionlessInputs.size() > 40) positionlessInputs.removeFirst();
            positionlessFrames = Math.min(40, positionlessFrames + 1);
            return new Sample(false, 0, false, 0, 0, false, false, 0, positionlessFrames);
        }
        if (hasPosition && (!Double.isFinite(packetX) || !Double.isFinite(packetY)
                || !Double.isFinite(packetZ))) {
            lastFrameAt = 0;
            reliable = false;
            initialized = false;
            positionlessFrames = 0;
            positionlessYawTransition = false;
            positionlessInputs.clear();
            return new Sample(false, 0, false, 0, 0, false, false, 0, 0);
        }
        int skippedFrames = positionlessFrames;
        positionlessFrames = 0;
        boolean uncertainHeadingPhase = positionlessYawTransition || yawChanged && skippedFrames > 0;
        positionlessYawTransition = false;
        long elapsedMillis = lastFrameAt > 0 ? Math.max(0, now - lastFrameAt) : 0;
        int physicsFrames = timing.physicsFrames(elapsedMillis, skippedFrames);
        double nextX = hasPosition ? packetX : x;
        double nextY = hasPosition ? packetY : y;
        double nextZ = hasPosition ? packetZ : z;
        double dx = nextX - x;
        double dy = nextY - y;
        double dz = nextZ - z;
        double startX = x, startY = y, startZ = z;
        double horizontal = Math.hypot(dx, dz);
        boolean externalTransition = externalMotion.rebase(impulse);
        List<MultiStepMotionPredictor.Frame> inputFrames = null;
        if (externalTransition || physicsFrames > 1 || skippedFrames > 0) {
            inputFrames = new ArrayList<>(positionlessInputs);
            inputFrames.add(MultiStepMotionPredictor.frame(
                    packetYawKnown ? yaw : environment == null ? Float.NaN : environment.yaw(),
                    inputs, environment, collisions));
        }
        positionlessInputs.clear();
        if (externalTransition) {
            previousMotion = externalMotion.seed(previousMotion, impulse);
            previousDy = previousMotion.dy();
        }
        if (collisions != null) previousMotion = new MotionPredictor.Motion(
                previousMotion.dx() + collisions.entityPushX(), previousMotion.dy(),
                previousMotion.dz() + collisions.entityPushZ());
        int tick = environment == null ? -1 : environment.tick();
        // Each flying packet is a possible client physics step. Several may
        // arrive during one server tick after network queuing.
        boolean adjacent = lastFrameAt > 0 && now - lastFrameAt < 250
                && lastTick >= 0 && tick >= lastTick;
        boolean collisionFresh = collisions != null && collisions.complete()
                && now >= collisions.capturedAt() && now - collisions.capturedAt() <= 200;
        boolean collisionUsable = collisions == null || collisionFresh;
        boolean ordinary = environment != null && environment.verticalAir()
                && now - environment.capturedAt() <= 200 && collisionUsable;
        // Terrain below/around the player should not disable Glide detection.
        // Entity pushes remain a bounded horizontal residual in the replay.
        boolean collisionAir = ordinary && !environment.ordinaryAir()
                && collisionFresh && collisions.blockGeometryComplete();
        boolean horizontalAir = environment != null
                && (environment.ordinaryAir() || collisionAir);
        float sneakScale = ordinary && environment.sneaking() ? environment.sneakingSpeed() : 1.0f;
        double legalAirTravel = ordinary && horizontalAir
                ? MotionPredictor.maximumAirTravelClient(previousMotion, environment.sprinting(),
                        environment.horizontalDrag(), sneakScale, environment.itemUseMultiplier(),
                        physicsFrames) * environment.stuckHorizontalMultiplier() : Double.POSITIVE_INFINITY;
        double allowedHorizontal = ordinary && horizontalAir
                ? (physicsFrames > 1 ? legalAirTravel + 0.08 + 0.02 * physicsFrames
                        : Math.max(1.0, legalAirTravel + 0.4))
                        + MotionCollisionSnapshot.horizontalVectorAllowance(collisions == null
                                ? 0 : collisions.entityPushHorizontalAllowance())
                : Double.POSITIVE_INFINITY;
        double legalAirVerticalTravel = ordinary
                ? MotionPredictor.maximumAirVerticalTravel(previousDy, environment.gravity(),
                        environment.verticalDrag(), environment.slowFalling(),
                        environment.levitationAmplifier(), environment.jumpStrength(), physicsFrames)
                        * environment.stuckVerticalMultiplier() : 0;
        boolean stickyWeb = ordinary && (environment.stuckHorizontalMultiplier() < 0.999f
                || environment.stuckVerticalMultiplier() < 0.999f);
        double allowedVertical = ordinary ? Math.max(1.0, legalAirVerticalTravel + 0.35) : 1.0;
        boolean nearSnapshot = environment != null
                && (environment.near(x, y, z) || environment.near(nextX, nextY, nextZ));
        boolean abrupt = !externalTransition && initialized && lastFrameAt > 0 && ordinary
                && (adjacent || physicsFrames > 1) && nearSnapshot
                && (horizontal > allowedHorizontal || Math.abs(dy) > allowedVertical);
        boolean samePhysicsModel = ordinary
                && gravity == environment.gravity()
                && slowFalling == environment.slowFalling()
                && levitationAmplifier == environment.levitationAmplifier()
                && horizontalDrag == environment.horizontalDrag()
                && verticalDrag == environment.verticalDrag()
                && stuckHorizontalMultiplier == environment.stuckHorizontalMultiplier()
                && stuckVerticalMultiplier == environment.stuckVerticalMultiplier();
        boolean stable = !externalTransition && skippedFrames == 0 && physicsFrames == 1
                && adjacent && samePhysicsModel
                && environment.near(nextX, nextY, nextZ)
                && Math.abs(dx) < 2 && Math.abs(dy) < 2 && Math.abs(dz) < 2;
        boolean aggregateRebased = !externalTransition && (physicsFrames > 1 || skippedFrames > 0) && initialized
                && lastFrameAt > 0 && samePhysicsModel && nearSnapshot;
        boolean horizontalStable = stable && horizontalAir
                && sprinting == environment.sprinting()
                && sneaking == environment.sneaking()
                && usingItem == environment.usingItem()
                && sneakingSpeed == environment.sneakingSpeed()
                && itemUseMultiplier == environment.itemUseMultiplier();
        boolean horizontalModelStable = environment != null && horizontalAir
                && sprinting == environment.sprinting()
                && sneaking == environment.sneaking()
                && usingItem == environment.usingItem()
                && sneakingSpeed == environment.sneakingSpeed()
                && itemUseMultiplier == environment.itemUseMultiplier();
        x = nextX; y = nextY; z = nextZ;
        lastFrameAt = now;
        lastTick = tick;
        initialized = true;
        if (environment != null) {
            sprinting = environment.sprinting();
            sneaking = environment.sneaking();
            usingItem = environment.usingItem();
            gravity = environment.gravity();
            slowFalling = environment.slowFalling();
            levitationAmplifier = environment.levitationAmplifier();
            horizontalDrag = environment.horizontalDrag();
            verticalDrag = environment.verticalDrag();
            sneakingSpeed = environment.sneakingSpeed();
            itemUseMultiplier = environment.itemUseMultiplier();
            stuckHorizontalMultiplier = environment.stuckHorizontalMultiplier();
            stuckVerticalMultiplier = environment.stuckVerticalMultiplier();
        }
        MotionPredictor.Motion actual = new MotionPredictor.Motion(dx, dy, dz);
        if (externalTransition) {
            List<MultiStepMotionPredictor.Frame> responseFrame =
                    MultiStepMotionPredictor.lastFrames(inputFrames, 1);
            MultiStepMotionPredictor.VerticalResult verticalResponse = environment != null
                    && environment.verticalAir() && now >= environment.capturedAt()
                    && now - environment.capturedAt() <= 200 && responseFrame != null
                    ? MultiStepMotionPredictor.airVertical(previousDy, dy, responseFrame,
                            startX, startY, startZ, true) : null;
            MultiStepMotionPredictor.Result horizontalResponse = environment != null
                    && environment.ordinaryAir() && horizontalModelStable && responseFrame != null
                    ? MultiStepMotionPredictor.airImpulseResponse(previousMotion, dx, dz, responseFrame,
                            startX, startY, startZ) : null;
            // Sending velocity does not establish which inbound movement first
            // includes it. An already in-flight pre-hit packet can arrive next,
            // even at low ping. Until transport acknowledgements delimit the
            // response window, this transition may seed prediction but cannot
            // establish knockback suppression.
            previousDy = verticalResponse != null && verticalResponse.offset() <= 0.08
                    ? verticalResponse.finalVelocity() : dy;
            previousMotion = horizontalResponse != null && horizontalResponse.offset() <= 0.08
                    ? horizontalResponse.finalVelocity() : actual;
            reliable = ordinary;
            return new Sample(false, 0, false, horizontal, dy, false, false, 0,
                    Math.max(skippedFrames, physicsFrames - 1));
        }
        if (!stable || !reliable) {
            MotionPredictor.Motion initialMotion = previousMotion;
            double initialVerticalVelocity = previousDy;
            reliable = stable || aggregateRebased;
            previousDy = aggregateRebased && skippedFrames > 0 ? dy / physicsFrames : dy;
            previousMotion = aggregateRebased && skippedFrames > 0
                    ? new MotionPredictor.Motion(dx / physicsFrames, dy / physicsFrames, dz / physicsFrames) : actual;
            if (aggregateRebased) {
                List<MultiStepMotionPredictor.Frame> replayFrames =
                        MultiStepMotionPredictor.lastFrames(inputFrames, physicsFrames);
                MultiStepMotionPredictor.VerticalResult verticalReplay =
                        replayFrames == null ? null
                                : MultiStepMotionPredictor.airVertical(initialVerticalVelocity, dy, replayFrames,
                                        startX, startY, startZ);
                if (verticalReplay != null) previousDy = verticalReplay.finalVelocity();
                double verticalExcess = stickyWeb
                        ? Math.max(0, Math.abs(dy) - legalAirVerticalTravel - 0.04)
                        : verticalReplay != null ? verticalReplay.offset()
                        : Math.max(0, Math.abs(dy) - legalAirVerticalTravel - 0.08);
                MultiStepMotionPredictor.Result replay = !stickyWeb && horizontalModelStable
                        && !uncertainHeadingPhase
                        && replayFrames != null
                        ? MultiStepMotionPredictor.air(initialMotion, dx, dz, replayFrames,
                                startX, startY, startZ) : null;
                if (replay != null) previousMotion = replay.finalVelocity();
                double entityPushAllowance = collisions == null ? 0
                        : collisions.entityPushHorizontalAllowance();
                double horizontalExcess = stickyWeb && horizontalModelStable
                        ? Math.max(0, MotionCollisionSnapshot.horizontalResidual(dx, dz, entityPushAllowance)
                                - legalAirTravel)
                        : replay != null ? replay.offset() : horizontalModelStable
                                ? Math.max(0, MotionCollisionSnapshot.horizontalResidual(
                                        dx, dz, entityPushAllowance) - legalAirTravel - 0.04) : 0;
                if (uncertainHeadingPhase)
                    previousMotion = new MotionPredictor.Motion(dx / physicsFrames,
                            dy / physicsFrames, dz / physicsFrames);
                return new Sample(true, verticalExcess, abrupt, horizontal, dy,
                        false, horizontalModelStable, horizontalExcess,
                        Math.max(skippedFrames, physicsFrames - 1));
            }
            return new Sample(false, 0, abrupt, horizontal, dy, false, false, 0, skippedFrames);
        }
        double expectedVertical = AirPredictor.nextDisplacement(previousDy,
                environment.gravity(), environment.verticalDrag(), environment.slowFalling(),
                environment.levitationAmplifier());
        if (stickyWeb) expectedVertical *= environment.stuckVerticalMultiplier();
        double offset = stickyWeb
                ? Math.max(0, Math.abs(dy - expectedVertical) - 0.04)
                : AirPredictor.offset(previousDy, dy,
                        environment.gravity(), environment.verticalDrag(),
                        environment.slowFalling(), environment.levitationAmplifier());
        double horizontalOffset = 0;
        double entityPushAllowance = collisions == null ? 0 : collisions.entityPushHorizontalAllowance();
        boolean collisionReplayAvailable = !stickyWeb && collisions != null
                && collisions.complete() && stable;
        if (stickyWeb && horizontalStable) {
            horizontalOffset = Math.max(0, MotionCollisionSnapshot.horizontalResidual(dx, dz,
                    entityPushAllowance) - legalAirTravel);
        } else if (horizontalStable && !collisionReplayAvailable) {
            float heading = packetYawKnown ? yaw : environment.yaw();
            horizontalOffset = bestAirHorizontalOffset(previousMotion, actual, dx, dz,
                    heading, alternateHeading, environment, sneakScale, inputs,
                    entityPushAllowance);
        }
        if (collisionReplayAvailable) {
            float heading = packetYawKnown ? yaw : environment.yaw();
            double[] collisionOffsets = bestCollisionAirOffsets(previousMotion, previousDy,
                    dx, dy, dz, startX, startY, startZ, heading, alternateHeading,
                    environment, sneakScale, inputs, collisions);
            if (Double.isFinite(collisionOffsets[0])) offset = collisionOffsets[0];
            if (horizontalStable && Double.isFinite(collisionOffsets[1]))
                horizontalOffset = collisionOffsets[1];
            else if (horizontalStable)
                horizontalOffset = bestAirHorizontalOffset(previousMotion, actual, dx, dz,
                        heading, alternateHeading, environment, sneakScale, inputs,
                        entityPushAllowance);
        }
        previousDy = dy;
        previousMotion = actual;
        return new Sample(true, offset, abrupt, horizontal, dy,
                false, horizontalStable, horizontalOffset, 0);
    }

    private static double bestAirHorizontalOffset(MotionPredictor.Motion previous,
                                                  MotionPredictor.Motion actual,
                                                  double dx, double dz, float heading,
                                                  float alternateHeading,
                                                  MotionEnvironment.Snapshot environment,
                                                  float sneakScale, JavaInputCapture.Window inputs,
                                                  double entityPushAllowance) {
        double best = predictAirHorizontalOffset(previous, actual, dx, dz, heading,
                environment, sneakScale, inputs, entityPushAllowance);
        if (best <= MotionPredictor.EARLY_EXIT_OFFSET) return best;
        if (Float.isFinite(alternateHeading))
            best = Math.min(best, predictAirHorizontalOffset(previous, actual, dx, dz,
                    alternateHeading, environment, sneakScale, inputs, entityPushAllowance));
        if (best <= MotionPredictor.EARLY_EXIT_OFFSET) return best;
        return best;
    }

    private double[] bestCollisionAirOffsets(MotionPredictor.Motion previous, double previousVertical,
                                             double actualX, double actualY, double actualZ,
                                             double startX, double startY, double startZ,
                                             float heading, float alternateHeading,
                                             MotionEnvironment.Snapshot environment, float sneakScale,
                                             JavaInputCapture.Window inputs,
                                             MotionCollisionSnapshot collisions) {
        double[] best = collisionAirOffsets(previous, previousVertical, actualX, actualY, actualZ,
                startX, startY, startZ, heading, environment, sneakScale, inputs, collisions);
        if (best[0] <= MotionPredictor.EARLY_EXIT_OFFSET
                && best[1] <= MotionPredictor.EARLY_EXIT_OFFSET) return best;
        if (Float.isFinite(alternateHeading))
            mergeOffsets(best, collisionAirOffsets(previous, previousVertical,
                    actualX, actualY, actualZ, startX, startY, startZ,
                    alternateHeading, environment, sneakScale, inputs, collisions));
        if (best[0] <= MotionPredictor.EARLY_EXIT_OFFSET
                && best[1] <= MotionPredictor.EARLY_EXIT_OFFSET) return best;
        return best;
    }

    private static void mergeOffsets(double[] best, double[] candidate) {
        best[0] = Math.min(best[0], candidate[0]);
        best[1] = Math.min(best[1], candidate[1]);
    }

    private static double predictAirHorizontalOffset(MotionPredictor.Motion previous,
                                                     MotionPredictor.Motion actual,
                                                     double dx, double dz, float heading,
                                                     MotionEnvironment.Snapshot environment,
                                                     float sneakScale, JavaInputCapture.Window inputs,
                                                     double entityPushAllowance) {
        if (inputs == null || inputs.current() == null) {
            MotionPredictor.Motion closest = MotionPredictor.predictAirClient(previous, actual,
                    heading, environment.sprinting(), environment.horizontalDrag(),
                    sneakScale, environment.itemUseMultiplier()).closest();
            return MotionCollisionSnapshot.horizontalResidual(
                    dx - closest.dx(), dz - closest.dz(), entityPushAllowance);
        }
        MotionPredictor.Motion closest = MotionPredictor.predictAirInputClient(previous, actual,
                heading, environment.sprinting(), environment.horizontalDrag(),
                sneakScale, environment.itemUseMultiplier(), inputs.current()).closest();
        double offset = MotionCollisionSnapshot.horizontalResidual(
                dx - closest.dx(), dz - closest.dz(), entityPushAllowance);
        if (inputs.previous() != null) {
            MotionPredictor.Motion previousClosest = MotionPredictor.predictAirInputClient(previous, actual,
                    heading, environment.sprinting(), environment.horizontalDrag(),
                    sneakScale, environment.itemUseMultiplier(), inputs.previous()).closest();
            offset = Math.min(offset, MotionCollisionSnapshot.horizontalResidual(
                    dx - previousClosest.dx(), dz - previousClosest.dz(), entityPushAllowance));
        }
        return offset;
    }
    private double[] collisionAirOffsets(MotionPredictor.Motion initialHorizontal, double initialVertical,
                                         double actualX, double actualY, double actualZ,
                                         double startX, double startY, double startZ, float heading,
                                         MotionEnvironment.Snapshot environment, float sneakScale,
                                         JavaInputCapture.Window inputs, MotionCollisionSnapshot collisions) {
        double best3d = Double.POSITIVE_INFINITY;
        double bestVertical = Double.POSITIVE_INFINITY;
        double bestHorizontal = Double.POSITIVE_INFINITY;
        double freeVertical = AirPredictor.nextDisplacement(initialVertical, environment.gravity(),
                environment.verticalDrag(), environment.slowFalling(), environment.levitationAmplifier());
        List<MotionPredictor.Input> candidates = MultiStepMotionPredictor.inputCandidates(inputs);
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        for (MotionPredictor.Input input : candidates) {
            MotionPredictor.Motion free = MotionPredictor.predictAirInputClient(initialHorizontal,
                    new MotionPredictor.Motion(0, 0, 0), heading, environment.sprinting(),
                    environment.horizontalDrag(), sneakScale, environment.itemUseMultiplier(), input).closest();
            int moveCount = collisions.resolveInto(startX, startY, startZ,
                    free.dx(), freeVertical, free.dz(), false, moves);
            for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                double verticalError = Math.abs(actualY - moves.y(moveIndex));
                double horizontalError = MotionCollisionSnapshot.horizontalResidual(
                        actualX - moves.x(moveIndex), actualZ - moves.z(moveIndex),
                        collisions.entityPushHorizontalAllowance());
                double error3d = Math.hypot(horizontalError, verticalError);
                if (error3d < best3d) {
                    best3d = error3d;
                    bestVertical = verticalError;
                    bestHorizontal = horizontalError;
                }
            }
            if (best3d <= MotionPredictor.EARLY_EXIT_OFFSET) break;
        }
        return new double[] {bestVertical, bestHorizontal};
    }
}
