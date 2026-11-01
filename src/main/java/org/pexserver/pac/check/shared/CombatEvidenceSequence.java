package org.pexserver.pac.check.shared;

/** Small consecutive-evidence counter with a bounded tick gap. */
final class CombatEvidenceSequence {
    private final int required;
    private final int maximumTickGap;
    private int count;
    private int lastTick;
    private boolean hasLastTick;

    CombatEvidenceSequence(int required, int maximumTickGap) {
        if (required < 1 || maximumTickGap < 0) throw new IllegalArgumentException();
        this.required = required;
        this.maximumTickGap = maximumTickGap;
    }

    boolean record(boolean suspicious, int tick) {
        if (!suspicious) {
            reset();
            return false;
        }
        if (hasLastTick && tick == lastTick) return false;
        count = hasLastTick && tick >= lastTick && tick - lastTick <= maximumTickGap
                ? Math.min(required, count + 1) : 1;
        lastTick = tick;
        hasLastTick = true;
        if (count < required) return false;
        count = Math.max(1, required - 1);
        return true;
    }

    int count() {
        return count;
    }

    void reset() {
        count = 0;
        hasLastTick = false;
    }
}
