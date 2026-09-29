package org.pexserver.pac.check.java.packet;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.pexserver.pac.PacPlugin;
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
    private static final class TransportState {
        final PacketRateWindow rate = new PacketRateWindow();
        long lastReportAt = Long.MIN_VALUE;
        boolean rejecting;
    }
    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, TransportState> transportStates = new ConcurrentHashMap<>();
    @Override public String key() { return "packet-flood"; }
    @Override public boolean supportsBedrock() { return false; }

    /**
     * High-ceiling DoS guard for all decoded Java Play packets. This is separate
     * from the much tighter movement-simulation rate, so normal inventory,
     * combat, plugin-message and latency bursts are not treated as movement
     * timer evidence.
     *
     * @return true when the packet was cancelled and caller should stop parsing.
     */
    public boolean inspectDecodedPacket(PacPlugin plugin, UUID uuid,
                                        PacketReceiveEvent event, long nowNanos) {
        if (plugin == null || uuid == null || event == null
                || !plugin.enabled(uuid, this) || plugin.isExempt(uuid, this))
            return false;

        TransportState state = transportStates.computeIfAbsent(uuid, ignored -> new TransportState());
        synchronized (state) {
            int packets = state.rate.record(nowNanos);
            int maximum = Math.max(400, Math.min(8_000,
                    plugin.getConfig().getInt("detectors.packet-flood.max-decoded-per-second", 1_200)));
            if (packets <= maximum) {
                state.rejecting = false;
                return false;
            }

            boolean firstRejected = !state.rejecting;
            state.rejecting = true;
            if (plugin.cancel(this, uuid)) event.setCancelled(true);
            if ((firstRejected || state.lastReportAt == Long.MIN_VALUE
                    || nowNanos - state.lastReportAt >= 1_000_000_000L)) {
                state.lastReportAt = nowNanos;
                flagLimited(uuid, () -> plugin.flag(uuid, this,
                        "decoded client packets exceeded " + maximum
                                + "/s transport ceiling: " + packets));
            }
            return event.isCancelled();
        }
    }

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

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        states.remove(uuid);
        transportStates.remove(uuid);
    }
}
