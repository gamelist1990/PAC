package org.pexserver.pac.check.java.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Rejects Wurst-style ground-critical and Mace fall-distance packet spoofing. */
public final class CriticalPacketCheck extends AbstractCheck implements PacketCheck {
    private static final long MAX_CRITICAL_SEQUENCE_AGE_MILLIS = 300;
    private static final long MAX_MACE_SEQUENCE_AGE_MILLIS = 500;
    private static final long MAX_MINI_JUMP_AGE_MILLIS = 350;
    private static final long MAX_ENVIRONMENT_AGE_MILLIS = 200;
    private static final double MACE_SPOOF_DELTA = Math.sqrt(500.0);
    private static final double[] CRITICAL_Y = {0.0625, -0.0625, 0.000011, -0.000011};
    private static final boolean[] CRITICAL_GROUND = {true, false, false, false};

    static record Step(double fromX, double fromY, double fromZ,
                       double dx, double dy, double dz,
                       boolean onGround, long at) { }

    private static final class State {
        long movementEpoch;
        double x, y, z;
        boolean initialized;
        long lastPositionAt;
        long criticalSpoofAt;
        long maceSpoofAt;
        long miniJumpAt;
        boolean lastOnGround;
        Step miniJumpStart;
        final ArrayDeque<Step> recentSteps = new ArrayDeque<>(4);

        State(long movementEpoch) { this.movementEpoch = movementEpoch; }

        void reset(double x, double y, double z, long now) {
            this.x = x;
            this.y = y;
            this.z = z;
            initialized = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
            lastPositionAt = now;
            criticalSpoofAt = 0;
            maceSpoofAt = 0;
            miniJumpAt = 0;
            miniJumpStart = null;
            lastOnGround = true;
            recentSteps.clear();
        }
    }

    private final org.pexserver.pac.PacPlugin plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public CriticalPacketCheck(org.pexserver.pac.PacPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String key() { return "critical-packet"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        if (context.event().isCancelled() || !context.flying().hasPositionChanged()) return;

        long now = System.currentTimeMillis();
        var location = context.location();
        var environment = context.plugin().environment().get(context.uuid());
        State state = states.compute(context.uuid(), (ignored, current) -> current == null
                || context.movementEpoch() >= 0 && current.movementEpoch != context.movementEpoch()
                ? new State(context.movementEpoch()) : current);
        synchronized (state) {
            state.movementEpoch = context.movementEpoch();
            if (context.externalImpulse() != null || context.plugin().recentExternalMotion(context.uuid())) {
                state.reset(location.getX(), location.getY(), location.getZ(), now);
                return;
            }
            if (environment == null || now < environment.capturedAt()
                    || now - environment.capturedAt() > MAX_ENVIRONMENT_AGE_MILLIS) {
                state.reset(Double.NaN, Double.NaN, Double.NaN, now);
                return;
            }
            if (!state.initialized) {
                state.reset(location.getX(), location.getY(), location.getZ(), now);
                return;
            }

            double dx = location.getX() - state.x;
            double dy = location.getY() - state.y;
            double dz = location.getZ() - state.z;
            if (now - state.lastPositionAt > 1_000) state.recentSteps.clear();

            if (isWurstMaceDmgDelta(dx, dy, dz)
                    && environment.near(state.x, state.y, state.z)) {
                state.maceSpoofAt = now;
                state.recentSteps.clear();
                flagLimited(context.uuid(), () -> context.plugin().flag(context.uuid(), this,
                        "MaceDMG spoofed a 22.36-block vertical movement packet",
                        Map.of("vertical_delta", dy, "horizontal_delta", Math.hypot(dx, dz))));
                if (context.plugin().cancel(this, context.uuid())) {
                    context.cancel(this);
                    context.plugin().correctJavaMovement(context.uuid(), environment.x(),
                            environment.y(), environment.z());
                    state.reset(environment.x(), environment.y(), environment.z(), now);
                    state.maceSpoofAt = now;
                } else {
                    state.x = location.getX();
                    state.y = location.getY();
                    state.z = location.getZ();
                    state.lastPositionAt = now;
                }
                return;
            }

            Step step = new Step(state.x, state.y, state.z, dx, dy, dz,
                    context.flying().isOnGround(), now);
            if (state.lastOnGround && isMiniJumpTakeoff(step)
                    && environment.ordinaryGround()
                    && environment.near(step.fromX(), step.fromY(), step.fromZ())) {
                state.miniJumpStart = step;
            } else if (state.miniJumpStart != null) {
                if (isMiniJumpContinuation(state.miniJumpStart, step)) {
                    state.miniJumpAt = now;
                    state.miniJumpStart = null;
                } else if (step.onGround() || now - state.miniJumpStart.at() > 200) {
                    state.miniJumpStart = null;
                }
            }
            state.lastOnGround = step.onGround();
            state.recentSteps.addLast(step);
            while (state.recentSteps.size() > 4) state.recentSteps.removeFirst();
            state.x = location.getX();
            state.y = location.getY();
            state.z = location.getZ();
            state.lastPositionAt = now;

            if (isWurstPacketCriticalSequence(state.recentSteps)) {
                Step first = state.recentSteps.peekFirst();
                state.criticalSpoofAt = now;
                flagLimited(context.uuid(), () -> context.plugin().flag(context.uuid(), this,
                        "Wurst Criticals packet-mode ground spoof sequence",
                        Map.of("sequence_packets", 4.0, "first_y_offset", first.dy())));
                if (context.plugin().cancel(this, context.uuid())) {
                    context.cancel(this);
                    context.plugin().correctJavaMovement(context.uuid(), first.fromX(),
                            first.fromY(), first.fromZ());
                    state.reset(first.fromX(), first.fromY(), first.fromZ(), now);
                    state.criticalSpoofAt = now;
                }
            }
        }
    }

    /** Called before the server handles an entity ATTACK packet. */
    public void onAttackPacket(UUID uuid, PacketReceiveEvent event) {
        if (uuid == null || event.isCancelled() || !plugin.enabled(uuid, this) || plugin.isExempt(uuid, this)) return;
        State state = states.get(uuid);
        if (state == null) return;

        long now = System.currentTimeMillis();
        synchronized (state) {
            boolean fakeCritical = state.criticalSpoofAt > 0 && now >= state.criticalSpoofAt
                    && now - state.criticalSpoofAt <= MAX_CRITICAL_SEQUENCE_AGE_MILLIS;
            boolean fakeMaceFall = state.maceSpoofAt > 0 && now >= state.maceSpoofAt
                    && now - state.maceSpoofAt <= MAX_MACE_SEQUENCE_AGE_MILLIS;
            boolean miniJump = state.miniJumpAt > 0 && now >= state.miniJumpAt
                    && now - state.miniJumpAt <= MAX_MINI_JUMP_AGE_MILLIS;
            state.criticalSpoofAt = 0;
            state.maceSpoofAt = 0;
            state.miniJumpAt = 0;
            if (!fakeCritical && !fakeMaceFall && !miniJump) return;

            if (plugin.cancel(this, uuid)) event.setCancelled(true);
            String reason = fakeMaceFall ? "attack followed MaceDMG vertical spoof"
                    : miniJump ? "attack followed nonvanilla miniature jump"
                    : "attack followed Criticals packet-mode ground spoof";
            flagLimited(uuid, () -> plugin.flag(uuid, this, reason));
        }
    }

    static boolean isWurstMaceDmgDelta(double dx, double dy, double dz) {
        return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                && Math.abs(dx) < 0.02 && Math.abs(dz) < 0.02
                && Math.abs(Math.abs(dy) - MACE_SPOOF_DELTA) <= 0.15;
    }

    static boolean isMiniJumpTakeoff(Step step) {
        // Wurst adds a 0.1 Y impulse; a normal player jump begins near 0.42.
        return !step.onGround() && step.dy() >= 0.075 && step.dy() <= 0.125;
    }

    static boolean isMiniJumpContinuation(Step takeoff, Step next) {
        return !next.onGround() && next.at() >= takeoff.at()
                && next.at() - takeoff.at() <= 200
                && next.dy() < takeoff.dy() - 0.035
                && next.dy() > -0.10;
    }

    static boolean isWurstPacketCriticalSequence(Collection<Step> steps) {
        if (steps.size() != 4) return false;
        Step first = null;
        Step previous = null;
        int i = 0;
        for (Step step : steps) {
            if (first == null) first = step;
            if (Math.abs(step.dx()) > 1.0e-5 || Math.abs(step.dz()) > 1.0e-5
                    || Math.abs(step.dy() - CRITICAL_Y[i]) > 1.0e-6
                    || step.onGround() != CRITICAL_GROUND[i]) return false;
            if (previous != null && (step.at() < previous.at()
                    || step.at() - previous.at() > MAX_CRITICAL_SEQUENCE_AGE_MILLIS)) return false;
            previous = step;
            i++;
        }
        return previous.at() - first.at() <= MAX_CRITICAL_SEQUENCE_AGE_MILLIS;
    }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        states.remove(uuid);
    }
}
