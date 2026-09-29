package org.pexserver.pac.movement;

/**
 * Correlates packet Y movement with server-authoritative special-surface
 * vertical motion. Minecraft 26.2 moved slime/bed bounce into generalized
 * collision restitution; this window deliberately consumes the server's own
 * velocity result instead of re-implementing an approximate pre-26.2 formula.
 */
public final class VerticalSurfaceMotionWindow {
    private static final long MAX_SAMPLE_AGE_MILLIS = 200L;
    private static final double MIN_BOUNCE_SERVER_VELOCITY = 0.12;
    private static final double BOUNCE_MIN_RATIO = 0.35;
    private static final double BOUNCE_ABSOLUTE_SLACK = 0.08;
    private static final double JUMP_SLACK = 0.09;
    private static final int REQUIRED_SUPPRESSED_BOUNCE_FRAMES = 2;

    public enum Anomaly {
        NONE,
        BOUNCE_SUPPRESSION,
        EXCESS_SPECIAL_SURFACE_JUMP
    }

    public record Surface(double x, double y, double z,
                          boolean onGround, boolean sneaking,
                          float bounceRestitution, float jumpFactor,
                          double legalJumpPower, double serverVelocityY,
                          long capturedAt) {
        public boolean fresh(long now) {
            return now >= capturedAt && now - capturedAt <= MAX_SAMPLE_AGE_MILLIS;
        }

        public boolean special() {
            return bounceRestitution > 0.0f || Math.abs(jumpFactor - 1.0f) > 1.0e-6;
        }

        public boolean near(double px, double py, double pz) {
            return Math.abs(x - px) <= 0.55
                    && Math.abs(y - py) <= 0.65
                    && Math.abs(z - pz) <= 0.55;
        }
    }

    public record Finding(Anomaly anomaly, double dy, double expected,
                          float restitution, float jumpFactor, int streak) {
        static Finding none() {
            return new Finding(Anomaly.NONE, 0, 0, 0, 1, 0);
        }
    }

    private boolean initialized;
    private double y;
    private long lastAt;
    private int suppressedBounceFrames;
    private long bounceObservedAt;

    public Finding accept(boolean hasPosition, double x, double nextY, double z,
                          Surface surface, long now, boolean invalidated) {
        if (invalidated || surface == null || !surface.fresh(now) || !surface.special()
                || !Double.isFinite(nextY)) {
            reset();
            if (hasPosition) seed(nextY, now);
            return Finding.none();
        }
        if (!hasPosition) return Finding.none();
        if (!initialized || now < lastAt || now - lastAt > 250) {
            seed(nextY, now);
            return Finding.none();
        }

        double dy = nextY - y;
        y = nextY;
        lastAt = now;

        // Manual jump from a settled special surface. Honey's jumpFactor=0.5,
        // while slime/bed keep 1.0. A legitimate restitution bounce is excluded
        // because the server already has a positive bounce velocity in that case.
        boolean settledGround = surface.onGround()
                && surface.serverVelocityY() <= MIN_BOUNCE_SERVER_VELOCITY
                && surface.near(x, nextY - dy, z);
        if (settledGround && dy > Math.max(0.08, surface.legalJumpPower() + JUMP_SLACK)) {
            suppressedBounceFrames = 0;
            return new Finding(Anomaly.EXCESS_SPECIAL_SURFACE_JUMP,
                    dy, surface.legalJumpPower(), surface.bounceRestitution(),
                    surface.jumpFactor(), 1);
        }

        // The server's own entity simulation has already produced a positive
        // restitution velocity. AntiBounce suppresses exactly that client-side.
        if (!surface.sneaking()
                && surface.bounceRestitution() > 0.0f
                && surface.serverVelocityY() >= MIN_BOUNCE_SERVER_VELOCITY) {
            if (bounceObservedAt == 0 || now - bounceObservedAt > 250) {
                bounceObservedAt = now;
                suppressedBounceFrames = 0;
            }
            double minimumExpected = Math.max(0.04,
                    surface.serverVelocityY() * BOUNCE_MIN_RATIO - BOUNCE_ABSOLUTE_SLACK);
            if (dy < minimumExpected) {
                suppressedBounceFrames++;
                if (suppressedBounceFrames >= REQUIRED_SUPPRESSED_BOUNCE_FRAMES) {
                    int streak = suppressedBounceFrames;
                    reset();
                    return new Finding(Anomaly.BOUNCE_SUPPRESSION, dy,
                            surface.serverVelocityY(), surface.bounceRestitution(),
                            surface.jumpFactor(), streak);
                }
            } else {
                suppressedBounceFrames = Math.max(0, suppressedBounceFrames - 1);
            }
        } else if (bounceObservedAt != 0 && now - bounceObservedAt > 250) {
            suppressedBounceFrames = 0;
            bounceObservedAt = 0;
        }

        return Finding.none();
    }

    public void reset() {
        initialized = false;
        lastAt = 0;
        suppressedBounceFrames = 0;
        bounceObservedAt = 0;
    }

    private void seed(double y, long now) {
        this.y = y;
        this.lastAt = now;
        this.initialized = true;
    }
}
