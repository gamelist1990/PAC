package org.pexserver.pac.check.java.movement;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.movement.MotionPredictor;
import org.pexserver.pac.movement.PredictionCorrectionLock;
import org.pexserver.pac.movement.WaterMotionSequence;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Predicts movement while submerged in stable water, including fluid currents. */
public final class WaterMotionPredictionCheck extends AbstractCheck implements PacketCheck {
    private static final class State {
        final long movementEpoch;
        final WaterMotionSequence sequence = new WaterMotionSequence();
        final PredictionCorrectionLock correctionLock = new PredictionCorrectionLock();
        double buffer;
        int suppressedCurrentFrames;
        WaterMotionSequence.Position verified;
        State(long movementEpoch) { this.movementEpoch = movementEpoch; }
    }

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    @Override public String key() { return "water-motion-prediction"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        if (context.event().isCancelled()) return;
        if (context.timingUncertain()) {
            states.remove(context.uuid());
            return;
        }
        if (context.plugin().environment().movementSuppressed(context.uuid())) {
            State pending = states.get(context.uuid());
            if (pending != null) {
                synchronized (pending) {
                    if (context.externalImpulse() != null || !context.plugin().cancel(this, context.uuid()))
                        pending.correctionLock.clear();
                    if (pending.correctionLock.holdDuringSync(
                            context.flying().hasPositionChanged(), true)) {
                        context.cancel(this);
                        return;
                    }
                }
            }
            if (pending != null) states.remove(context.uuid(), pending);
            return;
        }
        State state = states.compute(context.uuid(), (ignored, current) -> current == null
                || context.movementEpoch() >= 0 && current.movementEpoch != context.movementEpoch()
                ? new State(context.movementEpoch()) : current);
        synchronized (state) {
            state.sequence.timing(context.serverTiming());
            long now = System.currentTimeMillis();
            var previous = state.sequence.lastPosition();
            var previousMotion = state.sequence.motion();
            if (context.externalImpulse() != null || !context.plugin().cancel(this, context.uuid()))
                state.correctionLock.clear();
            var location = context.location();
            if (previous != null && context.flying().hasPositionChanged()
                    && context.plugin().environment().collisionChangeNear(context.uuid(),
                            previous.x(), previous.y(), previous.z(),
                            location.getX(), location.getY(), location.getZ(), now)) {
                states.remove(context.uuid(), state);
                return;
            }
            var sample = state.sequence.accept(context.flying().hasPositionChanged(),
                    location.getX(), location.getY(), location.getZ(), location.getYaw(),
                    context.plugin().waterMotion().get(context.uuid()),
                    context.plugin().environment().collisions(context.uuid()), context.inputs(),
                    context.externalImpulse(), now);
            var waterSnapshot = context.plugin().waterMotion().get(context.uuid());
            if (!context.flying().hasPositionChanged()) return;
            if (!sample.evaluated()) {
                state.suppressedCurrentFrames = 0;
                if (context.externalImpulse() != null) {
                    state.buffer = 0;
                    state.verified = null;
                } else {
                    state.buffer = Math.max(0, state.buffer - 0.5);
                    state.verified = null;
                }
                return;
            }

            state.suppressedCurrentFrames = sample.suppressedCurrent()
                    ? Math.min(20, state.suppressedCurrentFrames + 1)
                    : Math.max(0, state.suppressedCurrentFrames - 2);
            if (state.suppressedCurrentFrames >= 10) {
                state.suppressedCurrentFrames = 0;
                flagLimited(context, "water-current response suppressed across 10 movement frames");
                correct(context, state, previous, previousMotion, true);
                return;
            }

            // Fluid boundaries, swimming transitions and server/client water
            // drag do not line up perfectly on every tick. Keep this predictor
            // less sensitive than dry-ground checks while retaining sustained,
            // high-offset movement detection.
            double threshold = Math.max(0.05, context.plugin().waterMotionOffsetThreshold());
            if (waterSnapshot != null && !waterSnapshot.submerged()) state.buffer = 0;
            if (waterSnapshot != null && waterSnapshot.submerged()
                    && sample.offset() > threshold) {
                state.buffer += Math.min(2, sample.offset() / threshold);
            } else {
                if (state.buffer == 0) state.verified = previous;
                state.buffer = Math.max(0, state.buffer - 0.5);
            }
            if (state.correctionLock.reject(true, waterSnapshot != null
                    && waterSnapshot.submerged() && sample.offset() > threshold,
                    context.externalImpulse() != null)) {
                correct(context, state, null, previousMotion, false);
                return;
            }
            if (state.buffer < context.plugin().waterMotionBufferThreshold()) return;

            state.buffer = context.plugin().waterMotionBufferThreshold() * 0.5;
            flagLimited(context, () -> String.format(Locale.ROOT,
                    "water movement prediction offset=%.3f", sample.offset()));
            correct(context, state, previous, previousMotion, true);
        }
    }

    private void correct(PacketContext context, State state, WaterMotionSequence.Position previous,
                         MotionPredictor.Motion previousMotion, boolean enforce) {
        if (!context.plugin().cancel(this, context.uuid())) return;
        context.cancel(this);
        WaterMotionSequence.Position target = previous;
        if (target == null && state.correctionLock.active()) {
            var anchor = state.correctionLock.anchor();
            target = new WaterMotionSequence.Position(anchor.x(), anchor.y(), anchor.z());
        }
        if (target == null) {
            var accepted = context.plugin().environment().get(context.uuid());
            if (accepted != null) target = new WaterMotionSequence.Position(
                    accepted.x(), accepted.y(), accepted.z());
        }
        if (target == null) {
            context.plugin().correctJavaToSafeGround(context.uuid());
            return;
        }
        if (enforce) state.correctionLock.begin(new PredictionCorrectionLock.Position(
                target.x(), target.y(), target.z()));
        var anchor = state.correctionLock.anchor();
        if (anchor != null) target = new WaterMotionSequence.Position(anchor.x(), anchor.y(), anchor.z());
        state.verified = target;
        state.buffer = 0;
        state.sequence.rebaseForCorrection(target.x(), target.y(), target.z(), previousMotion,
                context.plugin().waterMotion().get(context.uuid()), System.currentTimeMillis());
        context.plugin().correctJavaMovement(context.uuid(), target.x(), target.y(), target.z());
    }

    @Override public void forget(UUID uuid) { super.forget(uuid); states.remove(uuid); }
}
