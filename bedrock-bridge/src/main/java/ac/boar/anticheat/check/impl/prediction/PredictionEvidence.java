package ac.boar.anticheat.check.impl.prediction;

import java.util.EnumMap;
import java.util.EnumSet;

/** A position miss alone cannot establish any particular movement violation. */
final class PredictionEvidence {
    enum Kind { Phase, Velocity, Collisions, Strafe, Speed, Flight }

    private final EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
    private long lastTick = Long.MIN_VALUE;

    EnumSet<Kind> observe(long tick, EnumSet<Kind> suspicious) {
        if (lastTick == Long.MAX_VALUE || tick != lastTick + 1) counts.clear();
        lastTick = tick;
        EnumSet<Kind> confirmed = EnumSet.noneOf(Kind.class);
        for (Kind kind : Kind.values()) {
            if (!suspicious.contains(kind)) {
                counts.remove(kind);
                continue;
            }
            int count = counts.getOrDefault(kind, 0) + 1;
            if (count >= 3) {
                confirmed.add(kind);
                count = 0;
            }
            counts.put(kind, count);
        }
        return confirmed;
    }

    void reset() {
        counts.clear();
        lastTick = Long.MIN_VALUE;
    }

    static double speedEnvelope(float movementSpeed, boolean onGround) {
        // Sprint jumps carry the launch impulse into the airborne ticks.
        return onGround ? Math.max(0.33, movementSpeed * 2.6)
                : Math.max(0.40, movementSpeed * 3.2);
    }
}
