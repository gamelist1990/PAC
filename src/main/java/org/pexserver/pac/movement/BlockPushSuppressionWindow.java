package org.pexserver.pac.movement;

/**
 * Consecutive-evidence filter for the client-only "push out of blocks" behavior.
 * The caller supplies the vanilla outward directions that are available at the
 * current suffocating overlap. A single counter-input frame is never evidence.
 */
public final class BlockPushSuppressionWindow {
    public static final int WEST = 1;
    public static final int EAST = 1 << 1;
    public static final int NORTH = 1 << 2;
    public static final int SOUTH = 1 << 3;

    private static final int REQUIRED_STABLE_FRAMES = 6;
    private static final int MAX_TICK_GAP = 2;
    private static final double MIN_OUTWARD_PROGRESS = 0.018;
    private static final double MAX_HORIZONTAL_FOR_SUPPRESSION = 0.085;

    public record Finding(boolean suspicious, int streak, double bestOutward,
                          double horizontal, int directions) {
        static Finding clear() {
            return new Finding(false, 0, 0, 0, 0);
        }
    }

    private int streak;
    private int lastTick = Integer.MIN_VALUE;

    public Finding sample(int tick, int directions, double dx, double dz,
                          boolean stillSuffocating, boolean invalidated) {
        if (invalidated || directions == 0 || !stillSuffocating
                || !Double.isFinite(dx) || !Double.isFinite(dz)) {
            reset();
            return Finding.clear();
        }
        if (lastTick != Integer.MIN_VALUE
                && (tick <= lastTick || tick - lastTick > MAX_TICK_GAP)) {
            streak = 0;
        }
        lastTick = tick;

        double bestOutward = bestOutward(directions, dx, dz);
        double horizontal = Math.hypot(dx, dz);
        boolean suppressed = bestOutward < MIN_OUTWARD_PROGRESS
                && horizontal < MAX_HORIZONTAL_FOR_SUPPRESSION;
        streak = suppressed ? Math.min(REQUIRED_STABLE_FRAMES, streak + 1)
                : Math.max(0, streak - 2);
        if (streak < REQUIRED_STABLE_FRAMES)
            return new Finding(false, streak, bestOutward, horizontal, directions);
        int findingStreak = streak;
        // Require several more stable frames before reporting again.
        streak = REQUIRED_STABLE_FRAMES / 2;
        return new Finding(true, findingStreak, bestOutward, horizontal, directions);
    }

    public void reset() {
        streak = 0;
        lastTick = Integer.MIN_VALUE;
    }

    static double bestOutward(int directions, double dx, double dz) {
        double best = Double.NEGATIVE_INFINITY;
        if ((directions & WEST) != 0) best = Math.max(best, -dx);
        if ((directions & EAST) != 0) best = Math.max(best, dx);
        if ((directions & NORTH) != 0) best = Math.max(best, -dz);
        if ((directions & SOUTH) != 0) best = Math.max(best, dz);
        return best == Double.NEGATIVE_INFINITY ? 0.0 : best;
    }
}
