package ac.boar.anticheat.prediction;

/** Physics advances by client ticks, regardless of transport pauses or packet bursts. */
public final class PredictionTickWindow {
    public enum Step { STALE, RESYNC, PREDICT }
    private long lastTick;
    private boolean initialized;
    private int settlingTicks;

    public Step observe(long tick) {
        if (tick < 0 || initialized && tick <= lastTick) return Step.STALE;
        if (!initialized || tick - lastTick != 1) {
            initialized = true;
            lastTick = tick;
            // Missing inputs cannot be reconstructed from the newest input.
            settlingTicks = 2;
            return Step.RESYNC;
        }
        lastTick = tick;
        if (settlingTicks > 0) {
            settlingTicks--;
            return Step.RESYNC;
        }
        return Step.PREDICT;
    }
}
