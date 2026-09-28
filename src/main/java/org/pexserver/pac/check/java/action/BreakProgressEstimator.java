package org.pexserver.pac.check.java.action;

/** Integrates Paper's per-tick break speed against client-time intervals. */
final class BreakProgressEstimator {
    record Estimate(double progress, float latestSpeed) { }

    private static final long CLIENT_TICK_NANOS = 50_000_000L;
    private double progress;
    private float speed;
    private long sampledAtNanos;

    BreakProgressEstimator(float initialSpeed, long nowNanos) {
        speed = usable(initialSpeed);
        sampledAtNanos = nowNanos;
    }

    synchronized void sample(float currentSpeed, long nowNanos) {
        advance(nowNanos);
        speed = usable(currentSpeed);
    }

    synchronized Estimate estimate(long nowNanos) {
        advance(nowNanos);
        return new Estimate(progress, speed);
    }

    private void advance(long nowNanos) {
        if (nowNanos <= sampledAtNanos) return;
        double clientTicks = (double) (nowNanos - sampledAtNanos) / CLIENT_TICK_NANOS;
        progress += speed * clientTicks;
        sampledAtNanos = nowNanos;
    }

    private static float usable(float speed) {
        return Float.isFinite(speed) && speed > 0 ? speed : 0;
    }
}
