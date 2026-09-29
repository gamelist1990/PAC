package org.pexserver.pac.movement;

/**
 * Correlates packet Y movement with server-authoritative special-surface state.
 * Minecraft 26.2 moved slime/bed bounce into generalized collision restitution,
 * so this models the observed landing transition rather than an obsolete fixed
 * slime multiplier.
 */
public final class VerticalSurfaceMotionWindow {
    private static final long MAX_SNAPSHOT_AGE_MILLIS = 200L;
    private static final long MAX_TRANSITION_MILLIS = 250L;
    private static final double MIN_FALL_FOR_BOUNCE = 0.18;
    private static final double JUMP_SLACK = 0.09;
    private static final double BOUNCE_MIN_RATIO = 0.25;
    private static final double BOUNCE_SLACK = 0.04;
    private static final int REQUIRED_EVENTS = 2;

    public enum Anomaly {
        NONE,
        BOUNCE_SUPPRESSION,
        EXCESS_SPECIAL_SURFACE_JUMP
    }

    public record Finding(Anomaly anomaly, double dy, double expected,
                          float restitution, int streak) {
        static Finding none() {
            return new Finding(Anomaly.NONE, 0, 0, 0, 0);
        }
    }

    private boolean initialized;
    private double y;
    private double lastDy;
    private long lastAt;

    private boolean groundArmed;
    private double armedJumpPower;
    private float armedRestitution;
    private long groundArmedAt;

    private boolean bounceExpected;
    private double expectedBounce;
    private float expectedRestitution;
    private long bounceDeadline;

    private int suppressedBounceEvents;
    private int excessJumpEvents;

    public Finding accept(boolean hasPosition, double x, double nextY, double z,
                          MotionEnvironment.Snapshot environment, long now,
                          boolean invalidated) {
        if (invalidated || environment == null || now < environment.capturedAt()
                || now - environment.capturedAt() > MAX_SNAPSHOT_AGE_MILLIS
                || !Double.isFinite(nextY)) {
            reset();
            if (hasPosition && Double.isFinite(nextY)) seed(nextY, now);
            return Finding.none();
        }
        if (!hasPosition) return Finding.none();
        if (!initialized || now < lastAt || now - lastAt > MAX_TRANSITION_MILLIS) {
            seed(nextY, now);
            rememberGround(environment, now);
            return Finding.none();
        }

        double dy = nextY - y;
        double previousDy = lastDy;
        boolean hadPendingBounce = bounceExpected;
        y = nextY;
        lastAt = now;

        if (bounceExpected) {
            if (now <= bounceDeadline) {
                double minimum = Math.max(0.035,
                        expectedBounce * BOUNCE_MIN_RATIO - BOUNCE_SLACK);
                if (dy < minimum) {
                    suppressedBounceEvents++;
                    bounceExpected = false;
                    if (suppressedBounceEvents >= REQUIRED_EVENTS) {
                        int streak = suppressedBounceEvents;
                        clearTransient();
                        lastDy = dy;
                        return new Finding(Anomaly.BOUNCE_SUPPRESSION, dy,
                                expectedBounce, expectedRestitution, streak);
                    }
                } else {
                    suppressedBounceEvents = Math.max(0, suppressedBounceEvents - 1);
                    bounceExpected = false;
                }
            } else {
                bounceExpected = false;
            }
        }

        if (groundArmed && now - groundArmedAt <= MAX_TRANSITION_MILLIS
                && dy > armedJumpPower + JUMP_SLACK) {
            excessJumpEvents++;
            groundArmed = false;
            if (excessJumpEvents >= REQUIRED_EVENTS) {
                int streak = excessJumpEvents;
                clearTransient();
                lastDy = dy;
                return new Finding(Anomaly.EXCESS_SPECIAL_SURFACE_JUMP,
                        dy, armedJumpPower, armedRestitution, streak);
            }
        } else if (dy > 0.035) {
            groundArmed = false;
        }

        // A downward frame followed by a server-confirmed landing on a
        // restitution surface must produce a positive next movement unless the
        // player is intentionally suppressing bounce (sneaking).
        if (environment.ordinaryGround() && environment.specialVerticalSurface()
                && environment.bounceRestitution() > 0.0f
                && !environment.sneaking() && !hadPendingBounce
                && previousDy < -MIN_FALL_FOR_BOUNCE
                && environment.near(x, nextY, z)) {
            expectedBounce = Math.max(0.0,
                    (-previousDy - environment.gravity()) * environment.bounceRestitution());
            if (expectedBounce >= 0.08) {
                expectedRestitution = environment.bounceRestitution();
                bounceExpected = true;
                bounceDeadline = now + MAX_TRANSITION_MILLIS;
            }
        }

        if (dy <= 0.03) rememberGround(environment, now);
        lastDy = dy;
        return Finding.none();
    }

    private void rememberGround(MotionEnvironment.Snapshot environment, long now) {
        if (environment.ordinaryGround() && environment.specialVerticalSurface()
                && Float.isFinite(environment.surfaceJumpStrength())
                && environment.surfaceJumpStrength() >= 0
                && environment.near(environment.x(), environment.y(), environment.z())) {
            groundArmed = true;
            armedJumpPower = environment.surfaceJumpStrength();
            armedRestitution = environment.bounceRestitution();
            groundArmedAt = now;
        }
    }

    public void reset() {
        initialized = false;
        y = 0;
        lastDy = 0;
        lastAt = 0;
        suppressedBounceEvents = 0;
        excessJumpEvents = 0;
        clearTransient();
    }

    private void clearTransient() {
        groundArmed = false;
        bounceExpected = false;
        groundArmedAt = 0;
        bounceDeadline = 0;
        expectedBounce = 0;
        expectedRestitution = 0;
    }

    private void seed(double y, long now) {
        this.y = y;
        this.lastAt = now;
        this.lastDy = 0;
        this.initialized = true;
    }
}
