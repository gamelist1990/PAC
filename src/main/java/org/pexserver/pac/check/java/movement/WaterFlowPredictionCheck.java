package org.pexserver.pac.check.java.movement;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.movement.FlowStagnationWindow;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Checks the no-input case of the server's water-current simulation. */
public final class WaterFlowPredictionCheck extends AbstractCheck implements PacketCheck {
    private record State(long movementEpoch, FlowStagnationWindow window) { }
    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    @Override public String key() { return "water-flow-prediction"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        if (context.event().isCancelled()) return;
        if (context.timingUncertain()) {
            states.remove(context.uuid());
            return;
        }
        if (context.externalImpulse() != null) { states.remove(context.uuid()); return; }
        if (context.plugin().environment().get(context.uuid()) == null) {
            states.remove(context.uuid());
            return;
        }
        State state = states.compute(context.uuid(), (ignored, current) -> current == null
                || context.movementEpoch() >= 0 && current.movementEpoch() != context.movementEpoch()
                ? new State(context.movementEpoch(), new FlowStagnationWindow()) : current);
        var location = context.location();
        boolean stagnant;
        synchronized (state.window()) {
            stagnant = state.window().accept(context.flying().hasPositionChanged(),
                    location.getX(), location.getZ(), location.getYaw(), context.inputs(),
                    context.plugin().waterFlow().get(context.uuid()), System.currentTimeMillis());
        }
        if (!stagnant) return;
        flagLimited(context, "missing fluid drift in unobstructed flowing water");
        if (context.plugin().cancel(this, context.uuid())) {
            context.cancel(this);
            context.plugin().correctJavaToSafeGround(context.uuid());
        }
    }

    @Override public void forget(UUID uuid) { super.forget(uuid); states.remove(uuid); }
}
