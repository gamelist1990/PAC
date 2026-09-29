package org.pexserver.pac.movement;

/**
 * Validates the short client-side Riptide launch against the exact impulse
 * Paper computed from the trident use. Collisions may reduce displacement, so
 * only motion above a conservative server-authoritative upper envelope counts.
 */
public final class RiptideMotionWindow {
    private static final long ACTIVE_MILLIS = 700L;
    private static final int REQUIRED_EXCESS_SAMPLES = 2;

    public record Sample(boolean evaluated, boolean excessive,
                         double horizontalPerFrame, double verticalPerFrame,
                         double allowedHorizontal, double allowedVertical,
                         double rollbackX, double rollbackY, double rollbackZ) {
        static Sample skipped() {
            return new Sample(false, false, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private boolean active;
    private double x, y, z;
    private double expectedX, expectedY, expectedZ;
    private long grantedAt, lastAt;
    private int excessStreak;

    public void grant(double x, double y, double z,
                      double expectedX, double expectedY, double expectedZ,
                      long now) {
        if (!finite(x, y, z, expectedX, expectedY, expectedZ)) {
            reset();
            return;
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.expectedX = expectedX;
        this.expectedY = expectedY;
        this.expectedZ = expectedZ;
        this.grantedAt = now;
        this.lastAt = now;
        this.excessStreak = 0;
        this.active = true;
    }

    public Sample accept(boolean hasPosition, double nextX, double nextY, double nextZ,
                         long now, boolean timingUncertain, boolean externalMotion) {
        if (!active) return Sample.skipped();
        if (timingUncertain || externalMotion || now < grantedAt
                || now - grantedAt > ACTIVE_MILLIS) {
            reset();
            return Sample.skipped();
        }
        if (!hasPosition) return Sample.skipped();
        if (!finite(nextX, nextY, nextZ)) {
            reset();
            return Sample.skipped();
        }

        double rollbackX = x, rollbackY = y, rollbackZ = z;
        long elapsed = Math.max(1L, now - lastAt);
        int frames = Math.max(1, Math.min(4, (int) Math.ceil(elapsed / 50.0)));
        int ageTicks = Math.max(1, Math.min(14,
                (int) Math.ceil(Math.max(1L, now - grantedAt) / 50.0)));

        double dx = nextX - x;
        double dy = nextY - y;
        double dz = nextZ - z;
        double horizontalPerFrame = Math.hypot(dx, dz) / frames;
        double verticalPerFrame = Math.abs(dy) / frames;

        double initialHorizontal = Math.hypot(expectedX, expectedZ);
        // Air/water input can add a small horizontal component. Gravity can
        // increase downward speed after a nearly-horizontal Riptide, so its
        // vertical allowance grows with age rather than treating the initial
        // launch vector as a permanent absolute cap.
        double allowedHorizontal = initialHorizontal + 0.14 + 0.03 * ageTicks;
        double allowedVertical = Math.abs(expectedY) + 0.12 + 0.08 * ageTicks;

        boolean suspicious = horizontalPerFrame > allowedHorizontal
                || verticalPerFrame > allowedVertical;
        excessStreak = suspicious ? Math.min(REQUIRED_EXCESS_SAMPLES, excessStreak + 1)
                : Math.max(0, excessStreak - 1);

        x = nextX;
        y = nextY;
        z = nextZ;
        lastAt = now;

        boolean excessive = excessStreak >= REQUIRED_EXCESS_SAMPLES;
        if (excessive) active = false;
        return new Sample(true, excessive, horizontalPerFrame, verticalPerFrame,
                allowedHorizontal, allowedVertical, rollbackX, rollbackY, rollbackZ);
    }

    public void reset() {
        active = false;
        grantedAt = 0;
        lastAt = 0;
        excessStreak = 0;
    }

    public boolean active() {
        return active;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
