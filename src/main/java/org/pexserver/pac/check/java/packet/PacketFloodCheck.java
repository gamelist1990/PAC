package org.pexserver.pac.check.java.packet;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.movement.GroundMotionSequence;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketFloodCheck extends AbstractCheck implements PacketCheck {
    private static final class State {
        final PacketRateWindow rate = new PacketRateWindow();
        long lastReportAt = Long.MIN_VALUE;
        boolean rejecting;
        GroundMotionSequence.Position verified;
    }
    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    @Override public String key() { return "packet-flood"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        // Invalid packets cancelled by an earlier check still consume network
        // and decode capacity, so they count toward the flood limit.
        boolean previouslyCancelled = context.event().isCancelled();
        State state = states.computeIfAbsent(context.uuid(), ignored -> new State());
        synchronized (state) {
            long now = System.nanoTime();
            int packets = state.rate.record(now);
            int maximum = context.plugin().maxPacketsPerSecond();
            if (packets > maximum) {
                boolean firstRejected = !state.rejecting;
                state.rejecting = true;
                boolean cancelFlood = context.plugin().cancel(this, context.uuid());
                if (cancelFlood) context.event().setCancelled(true);
                if (firstRejected && cancelFlood && !previouslyCancelled
                        && context.flying().hasPositionChanged() && state.verified != null)
                    context.plugin().correctJavaMovement(context.uuid(), state.verified.x(),
                            state.verified.y(), state.verified.z());
                if (state.lastReportAt == Long.MIN_VALUE || now - state.lastReportAt >= 500_000_000L) {
                    state.lastReportAt = now;
                    context.flag(this, "movement packets exceeded " + maximum
                            + "/s in a sliding second: " + packets);
                }
                return;
            }
            state.rejecting = false;
        }
    }

    /** Called after the complete packet-check chain accepts a coordinate packet. */
    public void acceptedPosition(UUID uuid, double x, double y, double z) {
        State state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            state.verified = new GroundMotionSequence.Position(x, y, z);
        }
    }

    @Override public void forget(UUID uuid) { super.forget(uuid); states.remove(uuid); }
}
