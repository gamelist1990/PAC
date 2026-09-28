package org.pexserver.pac.check.java.movement;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.movement.GroundMotionSequence;
import org.pexserver.pac.movement.MovementPacketTimer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Detects sped-up client simulation ticks, including legal-looking position steps. */
public final class TimerPredictionCheck extends AbstractCheck implements PacketCheck {
    private static final class State {
        final long movementEpoch;
        final MovementPacketTimer timer = new MovementPacketTimer();
        GroundMotionSequence.Position verified;
        State(long movementEpoch) { this.movementEpoch = movementEpoch; }
    }

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    @Override public String key() { return "timer-prediction"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        if (context.event().isCancelled()) return;
        // Rotation-only packets do not execute a client movement simulation
        // step. Counting them lets aim/rotation packet bursts look like timer.
        if (!context.flying().hasPositionChanged()) return;
        State state = states.compute(context.uuid(), (ignored, current) -> current == null
                || context.movementEpoch() >= 0 && current.movementEpoch != context.movementEpoch()
                ? new State(context.movementEpoch()) : current);
        synchronized (state) {
            if (state.timer.accept(System.nanoTime(), context.serverTiming().timerAllowanceMillis())) {
                flagLimited(context, "client movement clock exceeded server-lag-adjusted burst budget");
                if (context.plugin().cancel(this, context.uuid())) {
                    // Reject this simulation step before asking the server to
                    // restore the last packet that passed the timer budget.
                    context.cancel(this);
                    if (state.verified != null) {
                        context.plugin().correctJavaMovement(context.uuid(), state.verified.x(),
                                state.verified.y(), state.verified.z());
                    }
                }
                return;
            }
        }
    }

    /** Retain only coordinates accepted by every movement check. */
    public void acceptedPosition(UUID uuid, long movementEpoch, double x, double y, double z) {
        State state = states.get(uuid);
        if (state == null || state.movementEpoch != movementEpoch) return;
        synchronized (state) {
            state.verified = new GroundMotionSequence.Position(x, y, z);
        }
    }

    @Override public void forget(UUID uuid) { super.forget(uuid); states.remove(uuid); }
}
