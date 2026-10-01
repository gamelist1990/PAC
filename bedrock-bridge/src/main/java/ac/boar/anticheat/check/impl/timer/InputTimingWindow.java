package ac.boar.anticheat.check.impl.timer;

/** Monotonic transport timing: a burst needs sustained excess before reporting. */
public final class InputTimingWindow {
    private static final long TICK_NS = 50_000_000L;
    private static final long STARTUP_NS = 5_000_000_000L;
    private static final long CREDIT_NS = 2_000_000_000L;
    private static final long EXCESS_NS = 250_000_000L;
    private long startedAt, lastAt, lastTick, balance, excessSince;
    private boolean initialized;

    public boolean observe(long tick, long now, boolean exempt) {
        if (!initialized) {
            initialized = true;
            startedAt = now;
            reset(tick, now);
            return false;
        }
        long elapsed = now - lastAt;
        long ticks = tick - lastTick;
        if (exempt || now - startedAt < STARTUP_NS || ticks <= 0
                || ticks > 100 || elapsed < 0) {
            reset(tick, now);
            return false;
        }
        balance = Math.max(-CREDIT_NS, balance + ticks * TICK_NS - elapsed);
        lastAt = now;
        lastTick = tick;
        // Preserve time credit through a pause so queued packets can catch up.
        if (elapsed > 125_000_000L || balance <= EXCESS_NS) {
            excessSince = 0;
            return false;
        }
        if (excessSince == 0) excessSince = now;
        if (now - excessSince < 1_000_000_000L) return false;
        reset(tick, now);
        return true;
    }

    private void reset(long tick, long now) {
        lastTick = tick;
        lastAt = now;
        balance = 0;
        excessSince = 0;
    }
}
