package org.pexserver.pac.check.shared;

/** Requires consecutive confirmed rear-hemisphere hits before reporting an aim violation. */
final class RearAttackSequence {
    private static final int WINDOW_TICKS = 20;
    private int count;
    private int lastTick;
    private boolean hasLastTick;

    boolean record(boolean entirelyBehind, int tick) {
        if (!entirelyBehind) {
            reset();
            return false;
        }
        if (hasLastTick && tick == lastTick) return false;
        count = hasLastTick && tick >= lastTick && tick - lastTick <= WINDOW_TICKS
                ? Math.min(2, count + 1) : 1;
        lastTick = tick;
        hasLastTick = true;
        return count >= 2;
    }

    int count() { return count; }

    void reset() {
        count = 0;
        hasLastTick = false;
    }
}
