package org.pexserver.pac.check.java.movement;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.movement.GroundMotionSequence;
import org.pexserver.pac.movement.PredictionCorrectionLock;
import org.pexserver.pac.movement.RapidPositionJumpWindow;
import org.pexserver.pac.movement.SustainedSpeedEnvelope;
import org.pexserver.pac.movement.SustainedSpeedEvidence;
import org.pexserver.pac.movement.VanillaPositionBurst;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Candidate simulation for ordinary Java Edition ground movement. */
public final class MotionPredictionCheck extends AbstractCheck implements PacketCheck {
    private static final class State {
        volatile long movementEpoch;
        GroundMotionSequence sequence = new GroundMotionSequence();
        final SustainedSpeedEvidence speedEvidence = new SustainedSpeedEvidence();
        final SustainedSpeedEnvelope speedEnvelope = new SustainedSpeedEnvelope();
        final PredictionCorrectionLock correctionLock = new PredictionCorrectionLock();
        final VanillaPositionBurst positionBurst = new VanillaPositionBurst();
        final RapidPositionJumpWindow rapidJump = new RapidPositionJumpWindow();
        double buffer;
        long serverMotionSequence;
        boolean serverMotionActive;
        double pendingMomentum;
        long pendingImpulseSequence;
        long glideSequence;
        GroundMotionSequence.Position glidePosition;
        double glideHorizontalSpeed;
        GroundMotionSequence.Position verified;
        State(long movementEpoch) { this.movementEpoch = movementEpoch; }
    }

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    @Override public String key() { return "motion-prediction"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        // Packet checks share one receive event. A detector registered earlier
        // (for example timer or invalid-movement) may already have cancelled it;
        // prediction must still consume the coordinates or its state freezes and
        // this detector can remain permanently silent for the rest of the burst.
        var serverMotionAtEntry = context.plugin().serverMotionGrant(context.uuid());
        if (PredictionTransitionPolicy.suspendForAuthorizedFlight(
                context.plugin().environment().authorizedFlightMovement(context.uuid()),
                context.externalImpulse() != null, serverMotionAtEntry != null)) {
            states.remove(context.uuid());
            return;
        }
        State state = states.compute(context.uuid(), (ignored, current) -> current == null
                || context.movementEpoch() >= 0 && current.movementEpoch != context.movementEpoch()
                ? new State(context.movementEpoch()) : current);
        synchronized (state) {
            state.sequence.timing(context.serverTiming());
            state.speedEnvelope.timing(context.serverTiming());
            var location = context.location();
            long now = System.currentTimeMillis();
            long nowNanos = System.nanoTime();
            var rapidJump = state.rapidJump.accept(context.flying().hasPositionChanged(),
                    location.getX(), location.getY(), location.getZ(), nowNanos,
                    context.timingUncertain(),
                    context.plugin().environment().movementSuppressed(context.uuid()),
                    context.externalImpulse() != null || serverMotionAtEntry != null
                            || context.plugin().recentExternalMotion(context.uuid()),
                    context.plugin().environment().authorizedFlightMovement(context.uuid()));
            if (rapidJump.impossible()) {
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "impossible rapid position jump: distance=%.3f repeats=%d interval=%dms",
                        rapidJump.distance(), rapidJump.repeatedPositions(),
                        rapidJump.elapsedMillis()));
                correct(context, state, state.sequence.lastPosition(), state.sequence.motion(), true);
                return;
            }
            var burst = state.positionBurst.accept(context.flying().hasPositionChanged(),
                    location.getY(), nowNanos, context.timingUncertain());
            if (burst.impossible()) {
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "non-vanilla same-tick vertical sequence: rise=%.3f ratios=(%.3f, %.3f) packets=%d",
                        burst.rise(), burst.firstRatio(), burst.secondRatio(), burst.positions()));
                correct(context, state, state.sequence.lastPosition(), state.sequence.motion(), true);
                return;
            }
            var glide = context.plugin().environment().glideTransition(context.uuid());
            boolean gliding = glide != null && glide.gliding() && now >= glide.at() && now - glide.at() <= 200;
            boolean glideSettling = glide != null && glide.settling(now);
            if (gliding && context.flying().hasPositionChanged()) {
                if (state.glidePosition != null) {
                    // The dedicated Elytra check validates these glide steps.
                    // Bukkit velocity alone need not contain client movement.
                    state.glideHorizontalSpeed = Math.hypot(location.getX() - state.glidePosition.x(),
                            location.getZ() - state.glidePosition.z());
                }
                state.glidePosition = new GroundMotionSequence.Position(
                        location.getX(), location.getY(), location.getZ());
            }
            if (glide != null && (gliding || state.glideSequence != glide.sequence())) {
                state.glideSequence = glide.sequence();
                if (gliding || glideSettling) {
                    var baseline = state.sequence.lastPosition();
                    state.speedEnvelope.serverVelocity(Math.max(glide.horizontalSpeed(), state.glideHorizontalSpeed),
                            baseline == null ? location.getX() : baseline.x(),
                            baseline == null ? location.getY() : baseline.y(),
                            baseline == null ? location.getZ() : baseline.z(), System.nanoTime());
                    state.sequence = new GroundMotionSequence();
                    state.sequence.timing(context.serverTiming());
                    state.speedEvidence.reset();
                    state.buffer = 0;
                    state.correctionLock.clear();
                }
            }
            if (gliding) return;
            state.glidePosition = null;
            if (!glideSettling) {
                state.glideHorizontalSpeed = 0;
            }
            if (context.timingUncertain()) {
                var impulse = context.externalImpulse();
                if (impulse != null && impulse.sequence() != state.pendingImpulseSequence) {
                    state.pendingImpulseSequence = impulse.sequence();
                    double speed = Math.hypot(impulse.x(), impulse.z());
                    state.pendingMomentum = impulse.additive()
                            ? state.pendingMomentum + speed : Math.max(state.pendingMomentum, speed);
                }
                state.sequence = new GroundMotionSequence();
                state.speedEvidence.reset();
                state.buffer = 0;
                state.verified = null;
                state.correctionLock.clear();
                state.positionBurst.reset();
                state.rapidJump.reset();
                if (context.flying().hasPositionChanged()) {
                    if (state.pendingMomentum > 0) {
                        state.speedEnvelope.serverVelocity(state.pendingMomentum,
                                location.getX(), location.getY(), location.getZ(), System.nanoTime());
                    } else {
                        state.speedEnvelope.suspend(location.getX(), location.getY(),
                                location.getZ(), System.nanoTime());
                    }
                }
                return;
            }
            state.pendingMomentum = 0;
            if (context.plugin().waterMotion().wetNear(context.uuid(), location.getX(), location.getY(),
                    location.getZ(), now)) {
                state.speedEnvelope.reset();
                state.correctionLock.clear();
                states.remove(context.uuid(), state);
                return;
            }
            var serverMotion = context.plugin().serverMotionGrant(context.uuid());
            if (serverMotion != null) {
                if (state.serverMotionSequence != serverMotion.sequence()) {
                    state.serverMotionSequence = serverMotion.sequence();
                    var baseline = state.sequence.lastPosition();
                    state.speedEnvelope.serverVelocity(serverMotion.horizontalSpeed(),
                            baseline == null ? location.getX() : baseline.x(),
                            baseline == null ? location.getY() : baseline.y(),
                            baseline == null ? location.getZ() : baseline.z(), System.nanoTime());
                }
                state.serverMotionActive = true;
                state.buffer = 0;
                state.speedEvidence.reset();
                state.correctionLock.clear();
                if (context.flying().hasPositionChanged()) {
                    var envelope = state.speedEnvelope.accept(location.getX(), location.getY(),
                            location.getZ(), context.plugin().environment().get(context.uuid()),
                            context.plugin().environment().collisions(context.uuid()),
                            System.nanoTime(), false);
                    if (envelope.flagged()) {
                        flagLimited(context, () -> String.format(Locale.ROOT,
                                "server-velocity envelope exceeded: speed=%.3f legal=%.3f",
                                envelope.speed(), envelope.legalSpeed()));
                        correct(context, state, envelope.rollbackAnchor(),
                                state.sequence.motion(), true);
                    }
                }
                return;
            }
            if (state.serverMotionActive) {
                state.serverMotionActive = false;
                state.speedEnvelope.endServerVelocity();
                state.sequence = new GroundMotionSequence();
                state.verified = null;
            }
            // Self movement is already an ordered client physics stream. Ping
            // rollback is for remote combat entities; applying it here makes
            // the environment stale (permanently so above 400 ms RTT) and
            // compares movement with an older collision volume.
            var rawEnvironment = context.plugin().environment().get(context.uuid());
            var environment = clientSprintEnvironment(rawEnvironment, context.inputs());
            var previous = state.sequence.lastPosition();
            var previousMotion = state.sequence.motion();
            var collisions = context.plugin().environment().collisions(context.uuid());
            boolean collisionGeometryUncertain = context.flying().hasPositionChanged()
                    && context.externalImpulse() == null && previous != null
                    && context.plugin().environment().collisionChangeNear(context.uuid(),
                    previous.x(), previous.y(), previous.z(), location.getX(), location.getY(),
                    location.getZ(), now);
            if (collisionGeometryUncertain) {
                // Openable blocks can change shape between the packet's client
                // collision and the main-thread snapshot. Start a new speed
                // window instead of carrying the old surface's velocity cap.
                state.speedEnvelope.reset();
                state.speedEvidence.reset();
            }
            if (context.externalImpulse() != null) state.correctionLock.clear();
            if (!context.plugin().cancel(this, context.uuid())) state.correctionLock.clear();
            if (state.correctionLock.active()
                    && state.correctionLock.holdDuringSync(context.flying().hasPositionChanged(),
                    context.plugin().environment().movementSuppressed(context.uuid()))) {
                context.cancel(this);
                return;
            }
            SustainedSpeedEnvelope.Sample envelopeSample = null;
            if (context.flying().hasPositionChanged() && !collisionGeometryUncertain) {
                envelopeSample = state.speedEnvelope.accept(location.getX(), location.getY(),
                    location.getZ(), environment,
                    collisions, System.nanoTime(),
                    context.externalImpulse() != null);
            }
            if (glideSettling) {
                state.sequence = new GroundMotionSequence();
                state.speedEvidence.reset();
                state.buffer = 0;
                return;
            }
            var sample = state.sequence.accept(context.flying().hasPositionChanged(),
                    context.flying().hasRotationChanged(), location.getX(), location.getY(),
                    location.getZ(), location.getYaw(), environment, now,
                    context.inputs(), context.externalImpulse(),
                    collisions,
                    collisionGeometryUncertain);
            // A packet with no coordinates carries no displacement sample. Keep
            // the evidence buffer intact so packet-type changes cannot erase it.
            if (!context.flying().hasPositionChanged()) return;
            if (sample.externalImpulseMismatch()) {
                state.speedEvidence.reset();
                state.buffer = 0;
                var impulse = context.externalImpulse();
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "knockback response too small: server-velocity=(%.3f, %.3f, %.3f) displacement horizontal=%.3f vertical=%.3f",
                        impulse.x(), impulse.y(), impulse.z(), sample.horizontalDistance(),
                        sample.verticalDistance()));
                correct(context, state, previous, previousMotion, false);
                return;
            }
            if (sample.surfaceVerticalAnomaly() != GroundMotionSequence.SurfaceVerticalAnomaly.NONE) {
                state.speedEvidence.reset();
                String reason = switch (sample.surfaceVerticalAnomaly()) {
                    case BOUNCE_SUPPRESSED -> "expected surface bounce was suppressed";
                    case BOUNCE_EXCESS -> "surface rebound exceeded vanilla restitution";
                    case NONE -> "";
                };
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "%s: dy=%.3f horizontal=%.3f",
                        reason, sample.verticalDistance(), sample.horizontalDistance()));
                correct(context, state, previous, previousMotion, true);
                return;
            }
            if (sample.impossibleTakeoff()) {
                state.speedEvidence.reset();
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "impossible ground takeoff: dy=%.3f horizontal=%.3f",
                        sample.verticalDistance(), sample.horizontalDistance()));
                correct(context, state, previous, previousMotion, true);
                return;
            }
            if (sample.abrupt()) {
                state.speedEvidence.reset();
                flagLimited(context, () -> String.format(Locale.ROOT, "ground displacement=(%.3f, %.3f)",
                        sample.horizontalDistance(), sample.verticalDistance()));
                correct(context, state, previous, previousMotion, true);
                return;
            }
            double threshold = context.plugin().predictionOffsetThreshold();
            if (envelopeSample != null && envelopeSample.flagged()) {
                if (explainsSpeedEnvelope(sample, threshold)) {
                    state.speedEnvelope.reset();
                } else {
                    final var finding = envelopeSample;
                    flagLimited(context, () -> String.format(Locale.ROOT,
                            "sustained ground/air speed envelope exceeded: speed=%.3f legal=%.3f",
                            finding.speed(), finding.legalSpeed()));
                    var anchor = finding.rollbackAnchor();
                    correct(context, state, anchor == null ? previous : anchor, previousMotion, true);
                    return;
                }
            }
            if (!sample.evaluated()) {
                state.speedEvidence.reset();
                state.buffer = context.externalImpulse() != null
                        ? 0 : Math.max(0, state.buffer - 1);
                state.verified = null;
                return;
            }
            if (state.correctionLock.reject(true,
                    sample.offset() > threshold || sample.speedExcess() > 0.08,
                    context.externalImpulse() != null)) {
                correct(context, state, null, previousMotion, false);
                return;
            }
            if (state.speedEvidence.accept(sample.speedExcess())) {
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "sustained ground speed excess=%.3f", sample.speedExcess()));
                correct(context, state, previous, previousMotion, true);
                return;
            }
            if (sample.offset() > threshold) state.buffer += Math.min(2, sample.offset() / threshold);
            else {
                if (state.buffer == 0 && previous != null) {
                    if (sample.skippedFrames() > 0) {
                        var current = state.sequence.lastPosition();
                        state.verified = current == null ? previous
                                : new GroundMotionSequence.Position(current.x(), current.y(), current.z());
                    } else state.verified = previous;
                }
                state.buffer = Math.max(0, state.buffer - (sample.skippedFrames() > 0 ? 0.1 : 0.5));
            }
            if (state.buffer >= context.plugin().predictionBufferThreshold()) {
                state.buffer = context.plugin().predictionBufferThreshold() * 0.5;
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "horizontal prediction offset=%.3f", sample.offset()));
                correct(context, state, previous, previousMotion, true);
            }
        }
    }

    static boolean explainsSpeedEnvelope(GroundMotionSequence.Sample sample, double threshold) {
        // Skipped/rebased samples report offset zero without proving legal motion.
        // Likewise, aggregate or uncertain replays can leave offset zero while
        // their independent horizontal bound still shows sustained excess.
        return sample.evaluated() && !sample.abrupt() && sample.offset() <= threshold
                && sample.speedExcess() <= 0.006;
    }

    private void correct(PacketContext context, State state,
                         GroundMotionSequence.Position previous,
                         org.pexserver.pac.movement.MotionPredictor.Motion previousMotion,
                         boolean enforce) {
        if (!context.plugin().cancel(this, context.uuid())) return;
        context.cancel(this);
        GroundMotionSequence.Position target = previous;
        if (target == null && state.correctionLock.active()) {
            var anchor = state.correctionLock.anchor();
            target = new GroundMotionSequence.Position(anchor.x(), anchor.y(), anchor.z());
        }
        if (target == null) {
            var accepted = context.plugin().environment().get(context.uuid());
            if (accepted != null) target = new GroundMotionSequence.Position(
                    accepted.x(), accepted.y(), accepted.z());
        }
        if (target == null) {
            context.plugin().correctJavaToSafeGround(context.uuid());
            return;
        }
        if (enforce) state.correctionLock.begin(new PredictionCorrectionLock.Position(
                target.x(), target.y(), target.z()));
        var anchor = state.correctionLock.anchor();
        if (anchor != null) target = new GroundMotionSequence.Position(anchor.x(), anchor.y(), anchor.z());
        state.verified = target;
        state.buffer = 0;
        var environment = context.plugin().environment().get(context.uuid());
        state.sequence.rebase(target.x(), target.y(), target.z(), previousMotion.dx(),
                previousMotion.dy(), previousMotion.dz(),
                environment, System.currentTimeMillis());
        state.speedEnvelope.rebase(target.x(), target.y(), target.z(), System.nanoTime());
        context.plugin().correctJavaMovement(context.uuid(), target.x(), target.y(), target.z());
    }

    private static org.pexserver.pac.movement.MotionEnvironment.Snapshot clientSprintEnvironment(
            org.pexserver.pac.movement.MotionEnvironment.Snapshot environment,
            org.pexserver.pac.packet.JavaInputCapture.Window inputs) {
        if (environment == null || environment.sprinting() || inputs == null
                || inputs.current() == null || !inputs.current().forward()
                || !inputs.current().sprint()) return environment;
        return environment.withSprinting(true, environment.movementSpeed() * 1.3);
    }

    /** Keep this detector's evidence, but reset its simulated position after a PAC setback. */
    public void afterCorrection(UUID uuid, double x, double y, double z, long epoch,
                                double velocityX, double velocityY, double velocityZ,
                                org.pexserver.pac.movement.MotionEnvironment.Snapshot environment) {
        State state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            state.movementEpoch = epoch;
            state.sequence.rebase(x, y, z, velocityX, velocityY, velocityZ,
                    environment, System.currentTimeMillis());
            state.positionBurst.reset();
            state.rapidJump.reset();
            state.verified = new GroundMotionSequence.Position(x, y, z);
            state.speedEnvelope.rebase(x, y, z, System.nanoTime());
        }
    }

    @Override public void forget(UUID uuid) { super.forget(uuid); states.remove(uuid); }
}
