package org.pexserver.pac.movement;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-authoritative flight abilities, including short mode transitions. */
public final class FlightPermissionTracker {
    public record State(boolean allowed, boolean flying, float flySpeed,
                        double attributeSpeed, long changedAt, long capturedAt) { }

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    public void update(UUID uuid, boolean allowed, boolean flying, float flySpeed,
                       double attributeSpeed, long now) {
        if (uuid == null || !Float.isFinite(flySpeed)
                || !Double.isFinite(attributeSpeed)) return;
        states.compute(uuid, (ignored, previous) -> {
            long changedAt = previous == null ? 0 : previous.changedAt();
            if (previous != null && previous.flying() != flying) changedAt = now;
            return new State(allowed, flying, flySpeed, attributeSpeed, changedAt, now);
        });
    }

    public boolean authorizedMovement(UUID uuid, long now) {
        State state = states.get(uuid);
        return state != null && now >= state.capturedAt()
                && now - state.capturedAt() <= 250
                && (state.flying() || state.changedAt() > 0
                    && now - state.changedAt() <= 300);
    }

    public State get(UUID uuid) { return states.get(uuid); }
    public void forget(UUID uuid) { states.remove(uuid); }
}
