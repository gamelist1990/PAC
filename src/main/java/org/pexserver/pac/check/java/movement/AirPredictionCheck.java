package org.pexserver.pac.check.java.movement;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.movement.AirMotionSequence;
import org.pexserver.pac.movement.ElytraMotionSequence;
import org.pexserver.pac.movement.AirSilenceWindow;
import org.pexserver.pac.movement.AirHoverWindow;
import org.pexserver.pac.movement.AirGravityWindow;
import org.pexserver.pac.movement.PredictionCorrectionLock;
import org.pexserver.pac.movement.RiptideMotionWindow;
import org.pexserver.pac.packet.ExternalMotionTracker;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Checks vertical and horizontal physics steps in a clear air volume. */
public final class AirPredictionCheck extends AbstractCheck implements PacketCheck {
    private static final class State {
        volatile long movementEpoch;
        final AirMotionSequence sequence = new AirMotionSequence();
        final ElytraMotionSequence elytraSequence = new ElytraMotionSequence();
        final AirSilenceWindow silence = new AirSilenceWindow();
        final AirHoverWindow hover = new AirHoverWindow();
        final AirGravityWindow gravityWindow = new AirGravityWindow();
        final PredictionCorrectionLock correctionLock = new PredictionCorrectionLock();
        long lastHoverCorrectionAt;
        long serverMotionSequence;
        long lastAttackAt;
        int attackFrames;
        double verticalBuffer, horizontalBuffer, elytraBuffer;
        AirMotionSequence.Position verified;
        State(long movementEpoch) { this.movementEpoch = movementEpoch; }
    }

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RiptideMotionWindow> riptideWindows = new ConcurrentHashMap<>();

    @Override public String key() { return "air-prediction"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        // A preceding PAC detector may already have cancelled this event. Air
        // prediction still needs every ordered coordinate; dropping one breaks
        // velocity continuity and lets repeated Flight setbacks erase evidence.
        var serverMotion = context.plugin().serverMotionGrant(context.uuid());
        RiptideMotionWindow riptide = riptideWindows.get(context.uuid());
        if (riptide != null) {
            RiptideMotionWindow.Sample riptideSample;
            synchronized (riptide) {
                var location = context.location();
                riptideSample = riptide.accept(context.flying().hasPositionChanged(),
                        location.getX(), location.getY(), location.getZ(),
                        System.currentTimeMillis(), context.timingUncertain(),
                        context.externalImpulse() != null || serverMotion != null);
                if (!riptide.active()) riptideWindows.remove(context.uuid(), riptide);
            }
            if (riptideSample.excessive()) {
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "Riptide movement exceeded server-authoritative impulse: horizontal=%.3f/%.3f vertical=%.3f/%.3f",
                        riptideSample.horizontalPerFrame(), riptideSample.allowedHorizontal(),
                        riptideSample.verticalPerFrame(), riptideSample.allowedVertical()));
                if (context.plugin().cancel(this, context.uuid())) {
                    context.cancel(this);
                    context.plugin().correctJavaMovement(context.uuid(),
                            riptideSample.rollbackX(), riptideSample.rollbackY(),
                            riptideSample.rollbackZ());
                }
                return;
            }
        }
        if (context.timingUncertain() && context.externalImpulse() == null && serverMotion == null) {
            states.remove(context.uuid());
            return;
        }
        if (PredictionTransitionPolicy.suspendForAuthorizedFlight(
                context.plugin().environment().authorizedFlightMovement(context.uuid()),
                context.externalImpulse() != null, serverMotion != null)) {
            states.remove(context.uuid());
            return;
        }
        State state = states.compute(context.uuid(), (ignored, current) -> current == null
                || context.movementEpoch() >= 0 && current.movementEpoch != context.movementEpoch()
                ? new State(context.movementEpoch()) : current);
        synchronized (state) {
            state.sequence.timing(context.serverTiming());
            state.elytraSequence.timing(context.serverTiming());
            long now = System.currentTimeMillis();
            boolean hasPosition = context.flying().hasPositionChanged();
            // Velocity updates must be applied on the first coordinate packet:
            // AirMotionSequence buffers look-only packets before it reaches its
            // impulse handling. Consuming a grant on a rotation-only packet
            // would silently discard the launch vector before the 3D replay.
            ExternalMotionTracker.Impulse motionImpulse = hasPosition
                    ? context.externalImpulse() : null;
            if (hasPosition && serverMotion != null) {
                boolean alreadyApplied = state.serverMotionSequence == serverMotion.sequence();
                if (motionImpulse == null && !alreadyApplied) {
                    // PlayerVelocityEvent can arrive without a corresponding
                    // outgoing velocity packet on some Paper/plugin paths.
                    // The grant retains the exact vector so the first response
                    // still enters the physical simulation as its initial state.
                    motionImpulse = new ExternalMotionTracker.Impulse(serverMotion.sequence(),
                            serverMotion.velocityX(), serverMotion.velocityY(), serverMotion.velocityZ(),
                            now, false, false);
                } else if (motionImpulse != null && alreadyApplied
                        && sameVector(motionImpulse, serverMotion)) {
                    // The packet listener can observe a velocity after the
                    // Bukkit event fallback already seeded the same update.
                    // Do not apply that vector to the predictor twice.
                    motionImpulse = null;
                }
                state.serverMotionSequence = serverMotion.sequence();
            }
            if (context.timingUncertain() && motionImpulse == null) {
                states.remove(context.uuid(), state);
                return;
            }
            var location = context.location();
            // The packet stream is the local player's present-time simulation.
            // Rewinding it by half the RTT selects stale snapshots and disables
            // evaluation entirely once the selected frame exceeds freshness.
            var predictionEnvironment = context.plugin().environment().get(context.uuid());
            var predictionCollisions = context.plugin().environment().collisions(context.uuid());
            if (context.plugin().waterMotion().wetNear(context.uuid(), location.getX(), location.getY(),
                    location.getZ(), now)) {
                state.correctionLock.clear();
                states.remove(context.uuid(), state);
                return;
            }
            if (motionImpulse != null) state.correctionLock.clear();
            if (!context.plugin().cancel(this, context.uuid())) state.correctionLock.clear();
            state.silence.packet(now);
            if (state.correctionLock.active()
                    && state.correctionLock.holdDuringSync(context.flying().hasPositionChanged(),
                    context.plugin().environment().movementSuppressed(context.uuid()))) {
                context.cancel(this);
                return;
            }
            var elytraEnvironment = context.plugin().environment().elytra(context.uuid());
            ElytraMotionSequence.Position previousElytra = state.elytraSequence.lastPosition();
            var elytraSample = state.elytraSequence.accept(context.flying().hasPositionChanged(),
                    context.flying().hasRotationChanged(), location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(), elytraEnvironment,
                    context.plugin().environment().collisions(context.uuid()), motionImpulse, now);
            if (elytraEnvironment == null || !elytraEnvironment.gliding()
                    || motionImpulse != null) {
                state.elytraBuffer = 0;
            } else if (context.flying().hasPositionChanged() && elytraSample.evaluated()) {
                double elytraThreshold = context.plugin().elytraOffsetThreshold();
                double offset = Math.max(elytraSample.horizontalOffset(), elytraSample.verticalOffset());
                if (offset > elytraThreshold)
                    state.elytraBuffer += Math.min(2, offset / elytraThreshold);
                else state.elytraBuffer = Math.max(0, state.elytraBuffer - 0.5);
                if (state.elytraBuffer >= context.plugin().elytraBufferThreshold()) {
                    state.elytraBuffer = context.plugin().elytraBufferThreshold() * 0.5;
                    flagLimited(context, () -> String.format(Locale.ROOT,
                            "Elytra physics residual horizontal=%.3f vertical=%.3f firework=%s",
                            elytraSample.horizontalOffset(), elytraSample.verticalOffset(),
                            elytraEnvironment.fireworkBoost()));
                    AirMotionSequence.Position rollback = previousElytra == null ? state.sequence.lastPosition()
                            : new AirMotionSequence.Position(previousElytra.x(), previousElytra.y(), previousElytra.z());
                    correct(context, state, rollback, state.sequence.motion(),
                            state.sequence.verticalVelocity(), true);
                    return;
                }
            } else if (context.flying().hasPositionChanged())
                state.elytraBuffer = Math.max(0, state.elytraBuffer - 0.5);
            var previous = state.sequence.lastPosition();
            var previousMotion = state.sequence.motion();
            double previousVerticalVelocity = state.sequence.verticalVelocity();
            var environment = predictionEnvironment;
            boolean attackTransition = state.attackFrames > 0 && now - state.lastAttackAt <= 400;
            if (context.flying().hasPositionChanged() && state.attackFrames > 0) state.attackFrames--;
            var sample = state.sequence.accept(context.flying().hasPositionChanged(),
                    context.flying().hasRotationChanged(),
                    location.getX(), location.getY(), location.getZ(), location.getYaw(),
                    environment, now,
                    context.inputs(), motionImpulse,
                    predictionCollisions, attackTransition);
            if (motionImpulse != null) {
                // The impulse is the initial condition for this trajectory.
                // Drop the preceding free-fall fit, then evaluate subsequent
                // client steps against the new server-selected velocity.
                state.gravityWindow.reset();
                state.hover.reset();
            }
            boolean hoverEligible = flightHoverEligible(context, environment, now);
            // Sample the ordered packet trajectory. Reusing a main-thread Y
            // snapshot across several packets manufactures stationary segments.
            var hoverPosition = state.sequence.lastPosition();
            double hoverY = context.flying().hasPositionChanged() ? location.getY()
                    : hoverPosition != null ? hoverPosition.y()
                    : environment == null ? Double.NaN : environment.y();
            double gravityX = hoverPosition == null ? location.getX() : hoverPosition.x();
            double gravityZ = hoverPosition == null ? location.getZ() : hoverPosition.z();
            boolean freeFall = hoverEligible && !context.timingUncertain()
                    && !context.serverTiming().delayed()
                    && motionImpulse == null
                    // While this grant's tail is still part of the simulated
                    // velocity state, AirMotionSequence owns the full 3D path.
                    // The independent free-fall fit resumes after that modeled
                    // server velocity has decayed below its legal cutoff.
                    && serverMotion == null
                    && AirGravityWindow.clearVerticalCorridor(predictionCollisions,
                            previous == null ? gravityX : previous.x(),
                            previous == null ? hoverY : previous.y(),
                            previous == null ? gravityZ : previous.z(),
                            gravityX, hoverY, gravityZ, now);
            boolean gravityMismatch = context.flying().hasPositionChanged()
                    && state.gravityWindow.sample(freeFall, hoverY,
                            environment == null ? 0 : environment.gravity(),
                            environment == null ? 0 : environment.verticalDrag(), now,
                            sample.skippedFrames() + 1);
            if (gravityMismatch) {
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "continuous gravity trajectory mismatch; %s airOffset=%.4f dy=%.4f skipped=%d velocitySeq=%d",
                        state.gravityWindow.diagnostic(), sample.offset(), sample.verticalDistance(),
                        sample.skippedFrames(), state.serverMotionSequence));
                correct(context, state, previous, previousMotion, previousVerticalVelocity, true);
                return;
            }
            if (state.hover.sample(hoverEligible, hoverY, now)) {
                flagLimited(context, "sustained vertical hover in collision-free air (Flight/Anti-Kick)");
                if (context.flying().hasPositionChanged()) {
                    if (now - state.lastHoverCorrectionAt >= 250) {
                        state.lastHoverCorrectionAt = now;
                        correct(context, state, previous, previousMotion, previousVerticalVelocity, true);
                    } else context.cancel(this);
                    return;
                }
            }
            // Look-only and on-ground packets must not decay movement evidence.
            if (!context.flying().hasPositionChanged()) return;
            if (sample.externalImpulseMismatch()) {
                state.verticalBuffer = 0;
                state.horizontalBuffer = 0;
                var impulse = context.externalImpulse();
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "knockback response too small: server-velocity=(%.3f, %.3f, %.3f) displacement horizontal=%.3f vertical=%.3f",
                        impulse.x(), impulse.y(), impulse.z(), sample.horizontalDistance(),
                        sample.verticalDistance()));
                correct(context, state, previous, previousMotion, previousVerticalVelocity, false);
                return;
            }
            if (sample.abrupt()) {
                flagLimited(context, () -> String.format(Locale.ROOT, "air displacement=(%.3f, %.3f)",
                        sample.horizontalDistance(), sample.verticalDistance()));
                correct(context, state, previous, previousMotion, previousVerticalVelocity, true);
                return;
            }
            if (!sample.evaluated()) {
                state.verticalBuffer = context.externalImpulse() != null
                        ? 0 : Math.max(0, state.verticalBuffer - 1);
                state.horizontalBuffer = context.externalImpulse() != null
                        ? 0 : Math.max(0, state.horizontalBuffer - 1);
                state.verified = null;
                return;
            }
            double threshold = context.plugin().airOffsetThreshold();
            double horizontalThreshold = context.plugin().airHorizontalOffsetThreshold();
            boolean suspicious = sample.offset() > threshold
                    || sample.horizontalEvaluated() && sample.horizontalOffset() > horizontalThreshold;
            if (state.correctionLock.reject(sample.evaluated() || sample.horizontalEvaluated(),
                    suspicious, context.externalImpulse() != null)) {
                correct(context, state, null, previousMotion, previousVerticalVelocity, false);
                return;
            }
            if (state.verticalBuffer == 0 && state.horizontalBuffer == 0 && previous != null
                    && sample.offset() <= threshold
                    && (!sample.horizontalEvaluated() || sample.horizontalOffset() <= horizontalThreshold)) {
                if (sample.skippedFrames() > 0) {
                    var current = state.sequence.lastPosition();
                    state.verified = current == null ? previous
                            : new AirMotionSequence.Position(current.x(), current.y(), current.z());
                } else state.verified = previous;
            }
            if (sample.offset() > threshold) state.verticalBuffer += Math.min(2, sample.offset() / threshold);
            else state.verticalBuffer = Math.max(0,
                    state.verticalBuffer - (sample.skippedFrames() > 0 ? 0.1 : 0.5));
            if (state.verticalBuffer >= context.plugin().airBufferThreshold()) {
                state.verticalBuffer = context.plugin().airBufferThreshold() * 0.5;
                flagLimited(context, () -> String.format(Locale.ROOT,
                        "vertical prediction offset=%.3f", sample.offset()));
                correct(context, state, previous, previousMotion, previousVerticalVelocity, true);
                return;
            }
            if (sample.horizontalEvaluated()) {
                if (sample.horizontalOffset() > horizontalThreshold)
                    state.horizontalBuffer += Math.min(2, sample.horizontalOffset() / horizontalThreshold);
                else state.horizontalBuffer = Math.max(0,
                        state.horizontalBuffer - (sample.skippedFrames() > 0 ? 0.1 : 0.5));
                if (state.horizontalBuffer >= context.plugin().airBufferThreshold()) {
                    state.horizontalBuffer = context.plugin().airBufferThreshold() * 0.5;
                    flagLimited(context, () -> String.format(Locale.ROOT,
                            "air horizontal prediction offset=%.3f", sample.horizontalOffset()));
                    correct(context, state, previous, previousMotion, previousVerticalVelocity, true);
                }
            } else state.horizontalBuffer = Math.max(0,
                    state.horizontalBuffer - (sample.skippedFrames() > 0 ? 0.1 : 1));
        }
    }

    private void correct(PacketContext context, State state, AirMotionSequence.Position previous,
                         org.pexserver.pac.movement.MotionPredictor.Motion previousMotion,
                         double previousVerticalVelocity, boolean enforce) {
        if (!context.plugin().cancel(this, context.uuid())) return;
        context.cancel(this);
        AirMotionSequence.Position target = previous;
        if (target == null && state.correctionLock.active()) {
            var anchor = state.correctionLock.anchor();
            target = new AirMotionSequence.Position(anchor.x(), anchor.y(), anchor.z());
        }
        if (target == null) {
            var accepted = context.plugin().environment().get(context.uuid());
            if (accepted != null) target = new AirMotionSequence.Position(
                    accepted.x(), accepted.y(), accepted.z());
        }
        if (target == null) {
            context.plugin().correctJavaToSafeGround(context.uuid());
            return;
        }
        if (enforce) state.correctionLock.begin(new PredictionCorrectionLock.Position(
                target.x(), target.y(), target.z()));
        var anchor = state.correctionLock.anchor();
        if (anchor != null) target = new AirMotionSequence.Position(anchor.x(), anchor.y(), anchor.z());
        state.verified = target;
        state.verticalBuffer = 0;
        state.horizontalBuffer = 0;
        var environment = context.plugin().environment().get(context.uuid());
        state.sequence.rebase(target.x(), target.y(), target.z(), previousMotion.dx(),
                previousVerticalVelocity, previousMotion.dz(), environment, System.currentTimeMillis());
        state.elytraSequence.reset();
        context.plugin().correctJavaMovement(context.uuid(), target.x(), target.y(), target.z());
    }

    /** Retains accumulated evidence while aligning the prediction state to the setback. */
    public void afterCorrection(UUID uuid, double x, double y, double z, long epoch,
                                double velocityX, double velocityY, double velocityZ,
                                org.pexserver.pac.movement.MotionEnvironment.Snapshot environment) {
        State state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            state.movementEpoch = epoch;
            state.sequence.rebase(x, y, z, velocityX, velocityY, velocityZ,
                    environment, System.currentTimeMillis());
            state.elytraSequence.reset();
            state.verified = new AirMotionSequence.Position(x, y, z);
        }
    }

    /** Main-thread watchdog for a client that stops sending physics steps in midair. */
    public void sampleSilence(PacPlugin plugin) {
        long now = System.currentTimeMillis();
        if (plugin.environment().serverTiming().snapshot(System.nanoTime()).recovering()) {
            states.clear();
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid, this)) continue;
            if (plugin.environment().authorizedFlightMovement(uuid)) {
                states.remove(uuid);
                continue;
            }
            long epoch = plugin.environment().teleportGeneration(uuid);
            State state = states.compute(uuid, (ignored, current) -> current == null
                    || current.movementEpoch != epoch ? new State(epoch) : current);
            synchronized (state) {
                var environment = plugin.environment().get(uuid);
                boolean ordinaryAir = environment != null && environment.ordinaryAir()
                        && !plugin.recentExternalMotion(uuid)
                        && !plugin.recentPluginVelocity(uuid);
                if (state.silence.sample(ordinaryAir, now)) {
                    flagLimited(uuid, () -> plugin.flag(uuid, this,
                            "no movement packets while airborne for " + state.silence.silenceMillis(now) + "ms"));
                }
            }
        }
    }

    private boolean flightHoverEligible(PacketContext context,
                                        org.pexserver.pac.movement.MotionEnvironment.Snapshot environment,
                                        long now) {
        return context.externalImpulse() == null
                && flightHoverEligible(environment,
                        !context.plugin().recentExternalMotion(context.uuid()), now)
                && (!context.flying().hasPositionChanged()
                    || environment.near(context.location().getX(), context.location().getY(),
                            context.location().getZ()));
    }

    private static boolean sameVector(ExternalMotionTracker.Impulse impulse,
                                      org.pexserver.pac.packet.PacketChecks.ServerMotionGrant grant) {
        return Math.abs(impulse.x() - grant.velocityX()) < 1.0e-6
                && Math.abs(impulse.y() - grant.velocityY()) < 1.0e-6
                && Math.abs(impulse.z() - grant.velocityZ()) < 1.0e-6;
    }

    static boolean flightHoverEligible(
            org.pexserver.pac.movement.MotionEnvironment.Snapshot environment,
            boolean noRecentExternalMotion, long now) {
        return noRecentExternalMotion && environment != null && environment.gravityAirborne()
                && environment.stuckVerticalMultiplier() >= 0.999f
                && !environment.slowFalling() && environment.levitationAmplifier() < 0
                && environment.gravity() >= 0.04
                && now >= environment.capturedAt() && now - environment.capturedAt() <= 200;
    }

    /** The attacker's own horizontal velocity can be multiplied by 0.6 on a hit. */
    public void onAttackPacket(UUID uuid) {
        State state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            state.lastAttackAt = System.currentTimeMillis();
            state.attackFrames = 2;
        }
    }

    /** Capture the exact vanilla Riptide impulse before the client applies it. */
    public void onRiptide(UUID uuid, double x, double y, double z,
                          double expectedVelocityX, double expectedVelocityY,
                          double expectedVelocityZ, long now) {
        RiptideMotionWindow window = riptideWindows.computeIfAbsent(
                uuid, ignored -> new RiptideMotionWindow());
        synchronized (window) {
            window.grant(x, y, z, expectedVelocityX, expectedVelocityY,
                    expectedVelocityZ, now);
        }
    }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        states.remove(uuid);
        riptideWindows.remove(uuid);
    }
}
