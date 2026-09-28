package org.pexserver.pac.check.java.movement;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.movement.GroundClaimSequence;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Detects Wurst's repeated false on-ground packet flag while the server confirms support. */
public final class AntiHungerCheck extends AbstractCheck implements PacketCheck {
    private static final class State {
        final long movementEpoch;
        final GroundClaimSequence sequence = new GroundClaimSequence();
        State(long movementEpoch) { this.movementEpoch = movementEpoch; }
    }
    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    @Override public String key() { return "anti-hunger"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        if (context.event().isCancelled()) return;
        State state = states.compute(context.uuid(), (ignored, current) -> current == null
                || context.movementEpoch() >= 0 && current.movementEpoch != context.movementEpoch()
                ? new State(context.movementEpoch()) : current);
        var flying = context.flying();
        var location = context.location();
        long now = System.currentTimeMillis();
        var sample = state.sequence.accept(flying.hasPositionChanged(), location.getX(), location.getY(),
                location.getZ(), flying.isOnGround(), context.plugin().environment().get(context.uuid()), now);
        if (sample.confirmed()) {
            flagLimited(context.uuid(), () -> context.plugin().flag(context.uuid(), this,
                    "repeated false on-ground claim while server collision geometry confirms stable ground support",
                    Map.of("false_ground_claims", (double) sample.falseGroundClaims(),
                            "ground_support_offset", sample.supportOffset(),
                            "ground_repair_remaining_ms", 1500.0)));
        }
        if (sample.repairClaim() && context.plugin().cancel(this, context.uuid())) {
            // Correct only the falsified ground bit. Preserve valid coordinates,
            // rotation, and movement in the packet instead of dropping the move.
            flying.setOnGround(true);
            flying.write();
        }
    }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        states.remove(uuid);
    }
}
