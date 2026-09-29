package org.pexserver.pac.movement;

import org.pexserver.pac.packet.JavaInputCapture;
import org.pexserver.pac.packet.ExternalMotionTracker;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/** Replays each Java movement packet as a client physics step on ordinary ground. */
public final class GroundMotionSequence {
    private ServerTickTiming.Snapshot timing = ServerTickTiming.Snapshot.NORMAL;
    public void timing(ServerTickTiming.Snapshot timing) { this.timing = timing; }

    private static final double COLLISION_MATCH_EPSILON = 0.005;
    public record Sample(boolean evaluated, double offset, boolean abrupt,
                         double horizontalDistance, double verticalDistance, boolean externalImpulseMismatch,
                         boolean impossibleTakeoff, double speedExcess, int skippedFrames) { }
    public record Position(double x, double y, double z) { }

    private double x, y, z;
    private MotionPredictor.Motion previousMotion = new MotionPredictor.Motion(0, 0, 0);
    private final ExternalMotionWindow externalMotion = new ExternalMotionWindow();
    private final ArrayDeque<MultiStepMotionPredictor.Frame> positionlessInputs = new ArrayDeque<>();
    private long lastFrameAt;
    private int positionlessFrames;
    private int lastSnapshotTick = -1;
    private boolean reliable, initialized, sprinting, sneaking, usingItem, packetYawKnown;
    private double movementSpeed;
    private float groundFriction = 0.6f;
    private float horizontalDrag = 0.91f;
    private float sneakingSpeed = 0.3f, itemUseMultiplier = 1.0f;
    private float stuckHorizontalMultiplier = 1.0f;
    private float stuckVerticalMultiplier = 1.0f;
    private float blockSpeedFactor = 1.0f;
    private float yaw;
    private boolean takeoffArmed;
    private double takeoffY;
    private float takeoffJumpStrength;
    private long takeoffArmedAt;
    private long lastCombatImpulseAt;

    public Position lastPosition() { return initialized ? new Position(x, y, z) : null; }
    public MotionPredictor.Motion motion() { return previousMotion; }

    /** Rebase after PAC rejects movement while retaining trusted physics settings. */
    public void rebase(double x, double y, double z, double velocityX, double velocityY,
                       double velocityZ, MotionEnvironment.Snapshot environment, long now) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        this.x = x; this.y = y; this.z = z;
        previousMotion = new MotionPredictor.Motion(velocityX, velocityY, velocityZ);
        externalMotion.reset();
        positionlessInputs.clear();
        positionlessFrames = 0;
        lastFrameAt = now;
        lastSnapshotTick = environment == null ? -1 : environment.tick();
        initialized = true;
        reliable = environment != null && environment.ordinaryGround();
        takeoffArmed = false;
        takeoffY = y;
        takeoffArmedAt = 0;
        lastCombatImpulseAt = 0;
        if (environment != null) {
            packetYawKnown = Float.isFinite(environment.yaw());
            yaw = environment.yaw();
            sprinting = environment.sprinting();
            sneaking = environment.sneaking();
            usingItem = environment.usingItem();
            movementSpeed = environment.movementSpeed();
            groundFriction = environment.groundFriction();
            horizontalDrag = environment.horizontalDrag();
            sneakingSpeed = environment.sneakingSpeed();
            itemUseMultiplier = environment.itemUseMultiplier();
            stuckHorizontalMultiplier = environment.stuckHorizontalMultiplier();
            stuckVerticalMultiplier = environment.stuckVerticalMultiplier();
            blockSpeedFactor = environment.blockSpeedFactor();
            takeoffJumpStrength = environment.jumpStrength();
        }
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
                         MotionCollisionSnapshot collisions, boolean collisionGeometryUncertain) {
        if (hasRotation) {
            packetYawKnown = Float.isFinite(packetYaw);
            if (packetYawKnown) yaw = packetYaw;
        }
        // Look-only and on-ground packets do not expose a physics displacement.
        // Keep the last observed velocity until a packet carries coordinates.
        if (!hasPosition) {
            positionlessInputs.addLast(MultiStepMotionPredictor.frame(
                    packetYawKnown ? yaw : environment == null ? Float.NaN : environment.yaw(),
                    inputs, environment, collisions));
            if (positionlessInputs.size() > 40) positionlessInputs.removeFirst();
            positionlessFrames = Math.min(40, positionlessFrames + 1);
            return new Sample(false, 0, false, 0, 0, false, false, 0, positionlessFrames);
        }
        if (hasPosition && (!Double.isFinite(packetX) || !Double.isFinite(packetY)
                || !Double.isFinite(packetZ))) {
            initialized = false;
            reliable = false;
            takeoffArmed = false;
            lastFrameAt = 0;
            positionlessFrames = 0;
            positionlessInputs.clear();
            return new Sample(false, 0, false, 0, 0, false, false, 0, 0);
        }
        int skippedFrames = positionlessFrames;
        positionlessFrames = 0;
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
        boolean uncertainCollision = collisionGeometryUncertain && impulse == null && !externalTransition;
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
            if (impulse != null && impulse.combatKnockback()) {
                lastCombatImpulseAt = now;
                takeoffArmed = false;
            }
        }
                if (collisions != null) previousMotion = new MotionPredictor.Motion(
                                previousMotion.dx() + collisions.entityPushX(), previousMotion.dy(),
                                previousMotion.dz() + collisions.entityPushZ());
        int tick = environment == null ? -1 : environment.tick();
        boolean continuous = skippedFrames == 0 && physicsFrames == 1
                && lastFrameAt > 0 && now - lastFrameAt < 250
                && Math.abs(dx) < 2 && Math.abs(dy) < 2 && Math.abs(dz) < 2
                && lastSnapshotTick >= 0 && tick >= lastSnapshotTick;
        boolean collisionUsable = collisions == null
                || collisions.complete() && now - collisions.capturedAt() <= 200;
        boolean ordinary = environment != null && environment.ordinaryGround()
                && now - environment.capturedAt() <= 200 && collisionUsable;
        float sneakScale = ordinary && environment.sneaking() ? environment.sneakingSpeed() : 1.0f;
        double legalGroundTravel = ordinary
                ? MotionPredictor.maximumGroundTravelClient(previousMotion, environment.movementSpeed(),
                        environment.groundFriction(), environment.horizontalDrag(), sneakScale,
                        environment.itemUseMultiplier(), blockSpeedFactor, physicsFrames)
                        * environment.stuckHorizontalMultiplier() : 0;
        boolean stickyWeb = ordinary && environment.stuckHorizontalMultiplier() < 0.999f;
        double allowedHorizontal = ordinary
                ? (physicsFrames > 1 ? legalGroundTravel + 0.08 + 0.02 * physicsFrames
                        : Math.max(1.0, legalGroundTravel + 0.45))
                        + MotionCollisionSnapshot.horizontalVectorAllowance(collisions == null
                                ? 0 : collisions.entityPushHorizontalAllowance())
                : 1.0;
        double allowedVertical = Math.max(1.0, Math.abs(previousMotion.dy()) * 0.98 + 0.3)
                * physicsFrames;
        if (takeoffArmed) allowedVertical = Math.max(allowedVertical,
                (takeoffJumpStrength + 0.15) * physicsFrames);
        boolean nearSnapshot = environment != null
                && (environment.near(x, y, z) || environment.near(nextX, nextY, nextZ));
        boolean abrupt = !externalTransition && initialized && lastFrameAt > 0 && ordinary
                && (now - lastFrameAt < 250 || physicsFrames > 1)
                && nearSnapshot
                && (horizontal > allowedHorizontal || Math.abs(dy) > allowedVertical);
        // The last verified floor remains useful when the server's snapshot has
        // already changed to air. Disarm after the first upward physics frame.
        boolean combatImpulseSettling = lastCombatImpulseAt > 0 && now >= lastCombatImpulseAt
                && now - lastCombatImpulseAt < 500;
        // A missing collision snapshot uses the legacy jump envelope. When a
        // snapshot exists, only fresh and complete geometry may constrain it.
        boolean verifiedCollisionGeometry = collisions == null || collisions.complete()
                && collisions.blockGeometryComplete() && now >= collisions.capturedAt()
                && now - collisions.capturedAt() <= 200;
        boolean takeoffFrame = !externalTransition && !combatImpulseSettling
                && verifiedCollisionGeometry
                && (collisions == null || !collisions.hardEntityCollisionPossible())
                && skippedFrames == 0 && takeoffArmed && environment != null
                && environment.levitationAmplifier() < 0 && initialized
                && physicsFrames == 1 && lastFrameAt > 0 && now - lastFrameAt < 250
                && now - takeoffArmedAt < 250 && Math.abs(y - takeoffY) < 0.03
                && takeoffJumpStrength == environment.jumpStrength()
                && horizontal < 1.2 && dy > 0.035;
        double stepHeight = collisions == null ? 0.6 : Math.max(0, collisions.maxStep());
        boolean changedBlockCanExplainStep = uncertainCollision
                && Math.abs(dy) <= stepHeight + COLLISION_MATCH_EPSILON;
        boolean collisionStep = takeoffFrame && (changedBlockCanExplainStep
                || collisions != null && collisions.blockGeometryComplete()
                && (collisions.complete() && collisionMovementMatches(previousMotion, dx, dy, dz,
                            startX, startY, startZ, packetYawKnown ? yaw : environment.yaw(),
                            environment, sneakScale, inputs, collisions)
                    || now >= collisions.capturedAt() && now - collisions.capturedAt() <= 200
                            && collisionVerticalTakeoffMatches(dy, dx, dz, startX, startY, startZ,
                                    takeoffJumpStrength, collisions)
                    || Math.abs(dy) <= collisions.maxStep() + COLLISION_MATCH_EPSILON
                            && collisionGroundMovementMatches(previousMotion,
                            dx, dy, dz, startX, startY, startZ, environment,
                            sneakScale, collisions)));
        boolean impossibleTakeoff = !stickyWeb && takeoffFrame && !collisionStep
                && Math.abs(dy - takeoffJumpStrength) > 0.06;
        boolean sameGroundConditions = ordinary
                && sprinting == environment.sprinting() && sneaking == environment.sneaking()
                && usingItem == environment.usingItem()
                && groundFriction == environment.groundFriction()
                && horizontalDrag == environment.horizontalDrag()
                && sneakingSpeed == environment.sneakingSpeed()
                && itemUseMultiplier == environment.itemUseMultiplier()
                && stuckHorizontalMultiplier == environment.stuckHorizontalMultiplier()
                && stuckVerticalMultiplier == environment.stuckVerticalMultiplier()
                && blockSpeedFactor == environment.blockSpeedFactor()
                && takeoffJumpStrength == environment.jumpStrength()
                && Math.abs(movementSpeed - environment.movementSpeed()) < 1.0E-6;
        boolean sameGroundModel = sameGroundConditions && Math.abs(dy) <= 0.03;
        boolean smallCollisionStep = sameGroundConditions && Math.abs(dy) > 0.03 && Math.abs(dy) <= 0.12501
                && !uncertainCollision
                && collisions != null && collisions.blockGeometryComplete()
                && collisionGroundMovementMatches(previousMotion, dx, dy, dz, startX, startY, startZ,
                        environment, sneakScale, collisions);
        double legalStep = ordinary
                ? MotionPredictor.maximumGroundStepClient(previousMotion, environment.movementSpeed(),
                        environment.groundFriction(), environment.horizontalDrag(), sneakScale,
                        environment.itemUseMultiplier()) * environment.stuckHorizontalMultiplier() : 0;
        boolean headBonkSprintJump = ordinary && environment.sprinting()
                && !uncertainCollision && sameGroundConditions
                && dy >= -0.015 && dy <= 0.03 && collisions != null
                && collisions.blockGeometryComplete() && collisions.complete()
                && !collisions.hardEntityCollisionPossible()
                && collisionClippedSprintJump(dx, dy, dz, startX, startY, startZ,
                        environment.jumpStrength(), collisions);
        double pushAllowance = collisions == null ? 0 : collisions.entityPushHorizontalAllowance();
        double uncertainHorizontalLimit = legalStep
                + MotionCollisionSnapshot.horizontalVectorAllowance(pushAllowance)
                + COLLISION_MATCH_EPSILON;
        boolean uncertainGroundStep = uncertainCollision && sameGroundConditions
                && Math.abs(dy) > 0.03 && Math.abs(dy) <= stepHeight + COLLISION_MATCH_EPSILON
                && horizontal <= uncertainHorizontalLimit;
        boolean stable = !externalTransition && skippedFrames == 0 && continuous
                && (sameGroundModel || smallCollisionStep || uncertainGroundStep)
                && environment.near(nextX, nextY, nextZ);
        boolean aggregateRebased = !externalTransition && !uncertainCollision
                && (physicsFrames > 1 || skippedFrames > 0) && initialized
                && lastFrameAt > 0 && sameGroundModel && nearSnapshot;
        boolean firstPosition = !initialized;
        x = nextX; y = nextY; z = nextZ;
        lastFrameAt = now;
        lastSnapshotTick = tick;
        initialized = true;
        if (environment != null) {
            sprinting = environment.sprinting();
            sneaking = environment.sneaking();
            usingItem = environment.usingItem();
            movementSpeed = environment.movementSpeed();
            groundFriction = environment.groundFriction();
            horizontalDrag = environment.horizontalDrag();
            sneakingSpeed = environment.sneakingSpeed();
            itemUseMultiplier = environment.itemUseMultiplier();
            stuckHorizontalMultiplier = environment.stuckHorizontalMultiplier();
            stuckVerticalMultiplier = environment.stuckVerticalMultiplier();
            blockSpeedFactor = environment.blockSpeedFactor();
        }
        // The server can already report on-ground on the final *upward* frame
        // of a jump onto a higher block. That frame is a landing, not a new
        // grounded takeoff baseline. Arming here makes the next small Y delta
        // look like an impossible jump (especially beside a wall).
        if (ordinary && !combatImpulseSettling && (firstPosition || dy <= 0.03)
                && environment.near(nextX, nextY, nextZ)
                && Math.abs(nextY - environment.y()) < 0.03) {
            takeoffArmed = true;
            takeoffY = nextY;
            takeoffJumpStrength = environment.jumpStrength();
            takeoffArmedAt = now;
        } else if (environment == null || dy > 0.035 || Math.abs(nextY - takeoffY) > 0.03
                || now - takeoffArmedAt >= 250) {
            takeoffArmed = false;
        }
        MotionPredictor.Motion actual = new MotionPredictor.Motion(dx, dy, dz);
        if (externalTransition) {
            List<MultiStepMotionPredictor.Frame> responseFrame =
                    MultiStepMotionPredictor.lastFrames(inputFrames, 1);
            MultiStepMotionPredictor.Result response = environment != null && environment.ordinaryGround()
                    && now >= environment.capturedAt() && now - environment.capturedAt() <= 200
                    && responseFrame != null
                    ? MultiStepMotionPredictor.groundImpulseResponse(previousMotion, dx, dz,
                            responseFrame, startX, startY, startZ) : null;
            boolean trustedCollision = collisions != null && collisions.blockGeometryComplete()
                    && !collisions.hardEntityCollisionPossible()
                    && now >= collisions.capturedAt() && now - collisions.capturedAt() <= 200;
            boolean expectedKnockback = impulse != null && impulse.combatKnockback()
                    && Math.hypot(impulse.x(), impulse.z()) >= 0.15;
            boolean expectedVerticalKnockback = impulse != null && impulse.combatKnockback()
                    && Math.abs(impulse.y()) >= 0.15;
            double allowedResponseOffset = 0.10;
            double verticalResponseOffset = expectedVerticalKnockback && trustedCollision
                    && environment != null
                    ? collisionVerticalImpulseOffset(impulse, dx, dy, dz, startX, startY, startZ,
                            environment, collisions,
                            responseFrame != null && responseFrame.get(0).jumpPossible()) : 0;
            // A response-model difference alone is not proof of AntiKB: the first
            // damage step can include input, step-up, edge clipping, and packet
            // timing. Require both a poor simulation match and a materially
            // suppressed observed component. A resolved block collision is also
            // required for the vertical branch; uncovered AABB sweeps are unknown.
            boolean horizontalImpulseSuppressed = expectedKnockback && response != null
                    && response.offset() > allowedResponseOffset
                    && responseComponentSuppressed(horizontal, Math.hypot(impulse.x(), impulse.z()));
            boolean verticalImpulseSuppressed = expectedVerticalKnockback && trustedCollision
                    && environment != null && Double.isFinite(verticalResponseOffset)
                    && verticalResponseOffset > 0.10
                    && responseComponentSuppressed(Math.abs(dy), Math.abs(impulse.y()));
            boolean impulseMismatch = horizontalImpulseSuppressed || verticalImpulseSuppressed;
            if (response != null && response.offset() <= 0.08)
                previousMotion = postBlockSpeed(response.finalVelocity(), environment);
            else previousMotion = postBlockSpeed(actual, environment);
            // Compare the first combat response before rebasing. Then use the
            // observed movement as the next baseline so one hit cannot cascade
            // into repeated flags from the same stale impulse.
            reliable = ordinary;
            return new Sample(false, response == null ? 0 : response.offset(), false,
                    horizontal, dy, impulseMismatch, false, 0,
                    Math.max(skippedFrames, physicsFrames - 1));
        }
        if (!stable || !reliable) {
            reliable = stable || aggregateRebased;
            if (aggregateRebased) {
                double speedExcess = Math.max(0, MotionCollisionSnapshot.horizontalResidual(dx, dz,
                        collisions == null ? 0 : collisions.entityPushHorizontalAllowance())
                        - legalGroundTravel);
                List<MultiStepMotionPredictor.Frame> replayFrames =
                        MultiStepMotionPredictor.lastFrames(inputFrames, physicsFrames);
                MultiStepMotionPredictor.Result replay = replayFrames == null || uncertainCollision ? null
                        : MultiStepMotionPredictor.ground3d(previousMotion, dx, dy, dz, replayFrames,
                                startX, startY, startZ);
                if (replay != null) {
                    previousMotion = replay.finalVelocity();
                } else {
                    previousMotion = postBlockSpeed(skippedFrames > 0
                            ? new MotionPredictor.Motion(dx / physicsFrames, dy / physicsFrames, dz / physicsFrames)
                            : actual, environment);
                }
                return new Sample(true, stickyWeb || replay == null ? 0 : replay.offset(), abrupt,
                        horizontal, dy, false, impossibleTakeoff,
                        speedExcess, Math.max(skippedFrames, physicsFrames - 1));
            }
            previousMotion = postBlockSpeed(actual, environment);
            return new Sample(false, 0, abrupt, horizontal, dy, false,
                    impossibleTakeoff, 0, skippedFrames);
        }
        double horizontalResidual = MotionCollisionSnapshot.horizontalResidual(dx, dz,
                collisions == null ? 0 : collisions.entityPushHorizontalAllowance());
        double legalSingleStep = MotionPredictor.maximumGroundStepClient(
                previousMotion, environment.movementSpeed(), environment.groundFriction(),
                environment.horizontalDrag(), sneakScale, environment.itemUseMultiplier())
                * environment.stuckHorizontalMultiplier();
        double speedExcess = Math.max(0, horizontalResidual - legalSingleStep
                - (headBonkSprintJump ? 0.2 : 0));
        float heading = packetYawKnown ? yaw : environment.yaw();
        boolean collisionReplayAvailable = !uncertainCollision && !stickyWeb
                && collisions != null && collisions.complete();
        double offset;
        if (headBonkSprintJump && speedExcess <= 0.02) {
            // The AABB confirms a normal jump whose Y was clipped by the roof.
            // Its sprint impulse is a legal part of this horizontal step.
            offset = 0;
        } else if (uncertainCollision) {
            // A localized block update can invalidate only this packet's AABB
            // comparison. The independent legal-speed signal remains active.
            offset = 0;
        } else if (stickyWeb) {
            // Cobwebs scale the full movement vector after normal acceleration.
            // Keep ordinary prediction offsets out of this collision path and
            // use the scaled speed envelope to catch NoSlow instead.
            offset = 0;
        } else if (collisionReplayAvailable) {
            // The collision-resolved replay below is the authoritative result;
            // defer the cheaper open-space fallback unless replay has no path.
            offset = Double.POSITIVE_INFINITY;
        } else {
            offset = predictGroundOffset(previousMotion, actual, heading, environment,
                    sneakScale, inputs);
        }
        MotionPredictor.Motion nextMotion = actual;
        if (collisionReplayAvailable && !(headBonkSprintJump && speedExcess <= 0.02)) {
            MultiStepMotionPredictor.Result replay = collisionGroundPrediction(previousMotion, dx, dy, dz,
                    startX, startY, startZ, heading, environment, sneakScale, inputs, collisions);
            offset = replay != null ? replay.offset()
                    : predictGroundOffset(previousMotion, actual, heading, environment,
                            sneakScale, inputs);
            // Preserve the simulated velocity, including axes stopped by a
            // collision. Feeding the client's displacement back into physics
            // forgives part of a small speed injection on every following tick.
            // Unknown entity pushes still require an observed baseline.
            if (replay != null && collisions.entityPushHorizontalAllowance() == 0)
                nextMotion = replay.finalVelocity();
        }
        previousMotion = postBlockSpeed(nextMotion, environment);
        return new Sample(true, offset, abrupt, horizontal, dy, false,
                impossibleTakeoff, speedExcess, 0);
    }

    private MotionPredictor.Motion postBlockSpeed(MotionPredictor.Motion motion,
                                                  MotionEnvironment.Snapshot environment) {
        if (motion == null || environment == null || !environment.ordinaryGround())
            return motion;
        float factor = environment.blockSpeedFactor();
        if (!Float.isFinite(factor) || factor < 0 || factor > 4) factor = 1.0f;
        return new MotionPredictor.Motion(motion.dx() * factor, motion.dy(), motion.dz() * factor);
    }

    private double predictGroundOffset(MotionPredictor.Motion previous, MotionPredictor.Motion actual,
                                       float heading, MotionEnvironment.Snapshot environment,
                                       float sneakScale, JavaInputCapture.Window inputs) {
        if (inputs == null || inputs.current() == null) {
            return MotionPredictor.predictGroundClient(previous, actual, heading,
                    environment.movementSpeed(), environment.groundFriction(),
                    environment.horizontalDrag(), sneakScale,
                    environment.itemUseMultiplier()).offset();
        }
        double offset = MotionPredictor.predictGroundInputClient(previous, actual,
                heading, environment.movementSpeed(), environment.groundFriction(),
                environment.horizontalDrag(), sneakScale,
                environment.itemUseMultiplier(), inputs.current()).offset();
        if (inputs.previous() != null) offset = Math.min(offset,
                MotionPredictor.predictGroundInputClient(previous, actual,
                        heading, environment.movementSpeed(), environment.groundFriction(),
                        environment.horizontalDrag(), sneakScale,
                        environment.itemUseMultiplier(), inputs.previous()).offset());
        return offset;
    }

    private MultiStepMotionPredictor.Result collisionGroundPrediction(MotionPredictor.Motion initial, double actualX, double actualY, double actualZ,
                                         double startX, double startY, double startZ, float heading,
                                         MotionEnvironment.Snapshot environment, float sneakScale,
                                         JavaInputCapture.Window inputs, MotionCollisionSnapshot collisions) {
        double best = Double.POSITIVE_INFINITY;
        MotionPredictor.Motion bestVelocity = null;
        List<MotionPredictor.Input> candidates = MultiStepMotionPredictor.inputCandidates(inputs);
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        for (MotionPredictor.Input input : candidates) {
            MotionPredictor.Motion free = MotionPredictor.predictGroundInputClient(initial,
                    new MotionPredictor.Motion(0, 0, 0), heading, environment.movementSpeed(),
                    environment.groundFriction(), environment.horizontalDrag(), sneakScale,
                    environment.itemUseMultiplier(), input).closest();
            for (int verticalChoice = 0; verticalChoice < 2; verticalChoice++) {
                double wantedY = verticalChoice == 0 ? 0 : -environment.gravity();
                int moveCount = collisions.resolveInto(startX, startY, startZ,
                        free.dx(), wantedY, free.dz(), true, moves);
                for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                    double horizontal = MotionCollisionSnapshot.horizontalResidual(
                            actualX - moves.x(moveIndex), actualZ - moves.z(moveIndex),
                            collisions.entityPushHorizontalAllowance());
                    double offset = Math.hypot(horizontal, actualY - moves.y(moveIndex));
                    if (offset < best) {
                        best = offset;
                        bestVelocity = new MotionPredictor.Motion(
                                moves.x(moveIndex) == free.dx() ? free.dx() : 0,
                                moves.y(moveIndex) == wantedY ? wantedY : 0,
                                moves.z(moveIndex) == free.dz() ? free.dz() : 0);
                    }
                }
                // A near-perfect path is already below the detector threshold;
                // skip both the remaining vertical branch and input candidates.
                if (best <= MotionPredictor.EARLY_EXIT_OFFSET)
                    return new MultiStepMotionPredictor.Result(best, bestVelocity);
            }
        }
        return bestVelocity == null ? null : new MultiStepMotionPredictor.Result(best, bestVelocity);
    }

    private boolean collisionGroundMovementMatches(MotionPredictor.Motion initial,
                                                    double actualX, double actualY, double actualZ,
                                                    double startX, double startY, double startZ,
                                                    MotionEnvironment.Snapshot environment, float sneakScale,
                                                    MotionCollisionSnapshot collisions) {
        double maximumStep = MotionPredictor.maximumGroundStepClient(initial,
                environment.movementSpeed(), environment.groundFriction(), environment.horizontalDrag(),
                sneakScale, environment.itemUseMultiplier()) * environment.stuckHorizontalMultiplier();
        double entityPushAllowance = collisions.entityPushHorizontalAllowance();
        double horizontalTolerance = COLLISION_MATCH_EPSILON + entityPushAllowance;
        if (Math.hypot(actualX, actualZ) > maximumStep
                + MotionCollisionSnapshot.horizontalVectorAllowance(horizontalTolerance)) return false;
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        for (int verticalChoice = 0; verticalChoice < 2; verticalChoice++) {
            double wantedY = verticalChoice == 0 ? 0 : -environment.gravity();
            int moveCount = collisions.resolveInto(startX, startY, startZ,
                    actualX, wantedY, actualZ, true, moves);
            for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                if (Math.abs(actualX - moves.x(moveIndex)) <= horizontalTolerance
                        && Math.abs(actualY - moves.y(moveIndex)) <= COLLISION_MATCH_EPSILON
                        && Math.abs(actualZ - moves.z(moveIndex)) <= horizontalTolerance) return true;
            }
        }
        return false;
    }

    private double collisionVerticalImpulseOffset(ExternalMotionTracker.Impulse impulse,
                                                   double actualX, double actualY, double actualZ,
                                                   double startX, double startY, double startZ,
                                                   MotionEnvironment.Snapshot environment,
                                                   MotionCollisionSnapshot collisions,
                                                   boolean jumpPossible) {
        double postGravity = AirPredictor.nextDisplacement(impulse.y(), environment.gravity(),
                environment.verticalDrag(), environment.slowFalling(), environment.levitationAmplifier());
        double best = Double.POSITIVE_INFINITY;
        List<Double> expectedVertical = new ArrayList<>(List.of(impulse.y(), postGravity));
        if (jumpPossible) expectedVertical.add((double) environment.jumpStrength());
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        for (double expectedY : expectedVertical) {
            int moveCount = collisions.resolveInto(startX, startY, startZ,
                    actualX, expectedY, actualZ, true, moves);
            for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                best = Math.min(best, Math.abs(actualY - moves.y(moveIndex)));
            }
        }
        return Double.isFinite(best) ? best : Double.NaN;
    }

    private static boolean responseComponentSuppressed(double observed, double serverImpulse) {
        return Double.isFinite(observed) && Double.isFinite(serverImpulse)
                && serverImpulse >= 0.15
                && observed < Math.max(0.025, serverImpulse * 0.45);
    }

    private boolean collisionMovementMatches(MotionPredictor.Motion initial,
                                             double actualX, double actualY, double actualZ,
                                             double startX, double startY, double startZ, float heading,
                                             MotionEnvironment.Snapshot environment, float sneakScale,
                                             JavaInputCapture.Window inputs, MotionCollisionSnapshot collisions) {
        List<MotionPredictor.Input> candidates = MultiStepMotionPredictor.inputCandidates(inputs);
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        for (MotionPredictor.Input input : candidates) {
            MotionPredictor.Motion free = MotionPredictor.predictGroundInputClient(initial,
                    new MotionPredictor.Motion(0, 0, 0), heading, environment.movementSpeed(),
                    environment.groundFriction(), environment.horizontalDrag(), sneakScale,
                    environment.itemUseMultiplier(), input).closest();
            int moveCount = collisions.resolveInto(startX, startY, startZ,
                    free.dx(), environment.jumpStrength(), free.dz(), true, moves);
            for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                if (Math.abs(actualX - moves.x(moveIndex)) <= COLLISION_MATCH_EPSILON
                        && Math.abs(actualY - moves.y(moveIndex)) <= COLLISION_MATCH_EPSILON
                        && Math.abs(actualZ - moves.z(moveIndex)) <= COLLISION_MATCH_EPSILON) return true;
            }
        }
        return false;
    }

    /**
     * Entity pushes can make horizontal displacement differ from the player-input
     * simulation while the block AABBs still prove that a jump hit a low ceiling.
     * In that case validate the collision-clipped vertical component along the
     * observed path independently, rather than treating the legal head bonk as
     * an impossible jump.
     */
    private boolean collisionVerticalTakeoffMatches(double actualY,
                                                     double actualX, double actualZ,
                                                     double startX, double startY, double startZ,
                                                     double jumpStrength,
                                                     MotionCollisionSnapshot collisions) {
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        int moveCount = collisions.resolveInto(startX, startY, startZ,
                actualX, jumpStrength, actualZ, true, moves);
        for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
            double collisionY = moves.y(moveIndex);
            // A low ceiling clips the normal jump below jumpStrength; a step
            // collider can instead raise the player above it, up to maxStep.
            boolean collisionAdjustedTakeoff = collisionY > 0.035
                    && collisionY <= collisions.maxStep() + COLLISION_MATCH_EPSILON
                    && Math.abs(collisionY - jumpStrength) > 0.06;
            if (collisionAdjustedTakeoff
                    && Math.abs(actualY - collisionY) <= COLLISION_MATCH_EPSILON) return true;
        }
        return false;
    }

    private static boolean collisionClippedSprintJump(double dx, double dy, double dz,
                                                      double x, double y, double z,
                                                      float jumpStrength,
                                                      MotionCollisionSnapshot collisions) {
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        int count = collisions.resolveInto(x, y, z, dx, jumpStrength, dz, true, moves);
        for (int i = 0; i < count; i++) {
            if (moves.y(i) < jumpStrength - 0.04
                    && Math.abs(moves.x(i) - dx) <= COLLISION_MATCH_EPSILON
                    && Math.abs(moves.y(i) - dy) <= COLLISION_MATCH_EPSILON
                    && Math.abs(moves.z(i) - dz) <= COLLISION_MATCH_EPSILON) return true;
        }
        return false;
    }

}
