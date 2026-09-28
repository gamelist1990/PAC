package org.pexserver.pac.movement;

/**
 * Validates extra position packets that arrive inside one client physics tick.
 * Vanilla can batch packets after latency, but it cannot generate several new
 * jump integration states in a few milliseconds without an earlier delay.
 */
public final class VanillaPositionBurst {
    private static final long SAME_TICK_NANOS = 15_000_000L;
    private double baseY;
    private double firstY;
    private double secondY;
    private double lastY;
    private long lastAt;
    private int rapidPositions;

    public record Finding(boolean impossible, double rise, double firstRatio,
                          double secondRatio, int positions) {
        static Finding valid() { return new Finding(false, 0, 0, 0, 0); }
    }

    public Finding accept(boolean hasPosition, double y, long nowNanos, boolean timingUncertain) {
        if (!hasPosition || !Double.isFinite(y) || timingUncertain || nowNanos < lastAt) {
            reset();
            return Finding.valid();
        }
        if (lastAt == 0 || nowNanos - lastAt > SAME_TICK_NANOS) {
            baseY = y;
            rapidPositions = 0;
        } else {
            rapidPositions++;
            if (rapidPositions == 1) firstY = y;
            else if (rapidPositions == 2) secondY = y;
        }
        lastY = y;
        lastAt = nowNanos;
        if (rapidPositions < 3) return Finding.valid();

        double rise = lastY - baseY;
        if (rise < 0.75) return Finding.valid();
        double firstRatio = (firstY - baseY) / rise;
        double secondRatio = (secondY - baseY) / rise;
        // Wurst Legit Step emits 0.42*h and 0.753*h before the final h.
        // Ratios make the proof independent of its configured 1..10 height.
        boolean splitJump = Math.abs(firstRatio - 0.42) <= 0.035
                && Math.abs(secondRatio - 0.753) <= 0.035;
        return new Finding(splitJump, rise, firstRatio, secondRatio, rapidPositions + 1);
    }

    public void reset() {
        baseY = firstY = secondY = lastY = 0;
        lastAt = 0;
        rapidPositions = 0;
    }
}
