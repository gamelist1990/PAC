package org.pexserver.pac.check.shared;

/**
 * Detects the attack-only rotation pattern used by silent/on-tick aura modes:
 * snap to a target immediately before ATTACK, then restore the previous view.
 */
final class AttackRotationSequence {
    private static final long RESTORE_WINDOW_MILLIS = 140L;
    private static final long PATTERN_WINDOW_MILLIS = 4_000L;
    private static final double MINIMUM_SNAP_DEGREES = 8.0;
    private static final double MAXIMUM_RETURN_ERROR_DEGREES = 1.5;
    private static final double MAXIMUM_RETURN_ERROR_RATIO = 0.08;
    private static final double MINIMUM_RESTORE_RATIO = 0.82;
    private static final double MAXIMUM_REVERSAL_COSINE = -0.96;
    private static final int REQUIRED_PATTERNS = 3;

    record Finding(double snapDegrees, double restoreDegrees, double returnErrorDegrees,
                   double reversalCosine, long restoreMillis, int streak) { }

    private record Rotation(float yaw, float pitch, long at) { }
    private record Pending(Rotation before, Rotation attack, long at) { }

    private Rotation previous;
    private Rotation current;
    private Pending pending;
    private int streak;
    private long lastPatternAt;

    Finding sampleRotation(float yaw, float pitch, long now) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return null;
        Rotation next = new Rotation(yaw, pitch, now);
        Finding finding = evaluateRestore(next, now);
        if (current != null && (yaw != current.yaw() || pitch != current.pitch())) {
            previous = current;
        }
        current = next;
        return finding;
    }

    void attackPacket(long now) {
        if (previous == null || current == null || now < current.at()
                || now - current.at() > RESTORE_WINDOW_MILLIS) {
            pending = null;
            return;
        }
        double snapYaw = wrapDegrees(current.yaw() - previous.yaw());
        double snapPitch = current.pitch() - previous.pitch();
        double snap = Math.hypot(snapYaw, snapPitch);
        if (snap < MINIMUM_SNAP_DEGREES) {
            pending = null;
            return;
        }
        pending = new Pending(previous, current, now);
    }

    void reset() {
        previous = null;
        current = null;
        pending = null;
        streak = 0;
        lastPatternAt = 0L;
    }

    private Finding evaluateRestore(Rotation next, long now) {
        Pending candidate = pending;
        if (candidate == null) return null;
        long elapsed = now - candidate.at();
        if (elapsed < 0 || elapsed > RESTORE_WINDOW_MILLIS) {
            pending = null;
            streak = 0;
            return null;
        }

        double changed = angularDistance(candidate.attack(), next);
        if (changed < 0.01) return null;
        pending = null;

        double snapYaw = wrapDegrees(candidate.attack().yaw() - candidate.before().yaw());
        double snapPitch = candidate.attack().pitch() - candidate.before().pitch();
        double restoreYaw = wrapDegrees(next.yaw() - candidate.attack().yaw());
        double restorePitch = next.pitch() - candidate.attack().pitch();
        double snap = Math.hypot(snapYaw, snapPitch);
        double restore = Math.hypot(restoreYaw, restorePitch);
        double returnError = angularDistance(candidate.before(), next);
        if (snap < MINIMUM_SNAP_DEGREES || restore < snap * MINIMUM_RESTORE_RATIO) {
            streak = Math.max(0, streak - 1);
            return null;
        }
        double cosine = (snapYaw * restoreYaw + snapPitch * restorePitch) / (snap * restore);
        double allowedReturnError = Math.max(MAXIMUM_RETURN_ERROR_DEGREES,
                snap * MAXIMUM_RETURN_ERROR_RATIO);
        if (cosine > MAXIMUM_REVERSAL_COSINE || returnError > allowedReturnError) {
            streak = Math.max(0, streak - 1);
            return null;
        }

        streak = lastPatternAt > 0L && now >= lastPatternAt
                && now - lastPatternAt <= PATTERN_WINDOW_MILLIS ? streak + 1 : 1;
        lastPatternAt = now;
        if (streak < REQUIRED_PATTERNS) return null;
        int findingStreak = streak;
        streak = REQUIRED_PATTERNS - 1;
        return new Finding(snap, restore, returnError, cosine, elapsed, findingStreak);
    }

    private static double angularDistance(Rotation first, Rotation second) {
        return Math.hypot(wrapDegrees(second.yaw() - first.yaw()),
                second.pitch() - first.pitch());
    }

    static float wrapDegrees(float degrees) {
        degrees %= 360.0f;
        if (degrees >= 180.0f) degrees -= 360.0f;
        if (degrees < -180.0f) degrees += 360.0f;
        return degrees;
    }
}
