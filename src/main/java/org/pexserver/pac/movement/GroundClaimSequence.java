package org.pexserver.pac.movement;

/** Confirms repeated false on-ground claims against fresh server-side support geometry. */
public final class GroundClaimSequence {
    private static final long MAX_SAMPLE_GAP_MILLIS = 250;
    private static final long MIN_CLAIM_SPACING_MILLIS = 30;
    private static final long MIN_EVIDENCE_SPAN_MILLIS = 100;
    private static final long REPAIR_HOLD_MILLIS = 1_500;
    private static final double HORIZONTAL_ALIGNMENT = 0.75;
    private static final double VERTICAL_ALIGNMENT = 0.12;

    public record Sample(boolean confirmed, boolean repairClaim, int falseGroundClaims,
                         double supportOffset) { }

    private double x, y, z;
    private boolean initialized;
    private long lastSampleAt, firstFalseAt, lastFalseAt, repairUntil;
    private int falseClaims;

    public Sample accept(boolean hasPosition, double packetX, double packetY, double packetZ,
                         boolean claimedGround, MotionEnvironment.Snapshot environment, long now) {
        if (hasPosition && (!Double.isFinite(packetX) || !Double.isFinite(packetY)
                || !Double.isFinite(packetZ))) {
            reset();
            return new Sample(false, false, 0, Double.POSITIVE_INFINITY);
        }
        if (lastSampleAt > 0 && (now < lastSampleAt || now - lastSampleAt > MAX_SAMPLE_GAP_MILLIS))
            clearEvidence();

        double nextX = hasPosition ? packetX : initialized ? x
                : environment == null ? Double.NaN : environment.x();
        double nextY = hasPosition ? packetY : initialized ? y
                : environment == null ? Double.NaN : environment.y();
        double nextZ = hasPosition ? packetZ : initialized ? z
                : environment == null ? Double.NaN : environment.z();
        double supportOffset = environment == null || !finite(nextX, nextY, nextZ)
                ? Double.POSITIVE_INFINITY
                : Math.sqrt(square(environment.x() - nextX) + square(environment.y() - nextY)
                        + square(environment.z() - nextZ));

        boolean fresh = environment != null && now >= environment.capturedAt()
                && now - environment.capturedAt() <= 200;
        boolean supported = fresh && environment.ordinaryGround()
                && Math.abs(environment.x() - nextX) <= HORIZONTAL_ALIGNMENT
                && Math.abs(environment.z() - nextZ) <= HORIZONTAL_ALIGNMENT
                && Math.abs(environment.y() - nextY) <= VERTICAL_ALIGNMENT;
        boolean stableY = hasPosition
                ? initialized ? Math.abs(nextY - y) <= 0.03
                        : environment != null && Math.abs(nextY - environment.y()) <= VERTICAL_ALIGNMENT
                : initialized && environment != null
                        && Math.abs(nextY - environment.y()) <= VERTICAL_ALIGNMENT;
        boolean candidate = supported && stableY && !claimedGround;

        if (!candidate) {
            clearEvidence();
        } else if (firstFalseAt == 0 || now < lastFalseAt
                || now - firstFalseAt > MAX_SAMPLE_GAP_MILLIS
                || now - lastFalseAt > MAX_SAMPLE_GAP_MILLIS) {
            firstFalseAt = now;
            lastFalseAt = now;
            falseClaims = 1;
        } else if (now - lastFalseAt >= MIN_CLAIM_SPACING_MILLIS) {
            lastFalseAt = now;
            falseClaims = Math.min(20, falseClaims + 1);
        }

        boolean confirmed = candidate && falseClaims >= 4
                && now - firstFalseAt >= MIN_EVIDENCE_SPAN_MILLIS;
        if (confirmed) repairUntil = Math.max(repairUntil, now + REPAIR_HOLD_MILLIS);
        boolean repairClaim = candidate && now <= repairUntil;

        if (hasPosition) {
            x = nextX;
            y = nextY;
            z = nextZ;
            initialized = true;
        } else if (!initialized && finite(nextX, nextY, nextZ)) {
            x = nextX;
            y = nextY;
            z = nextZ;
            initialized = true;
        }
        lastSampleAt = now;
        return new Sample(confirmed, repairClaim, falseClaims, supportOffset);
    }

    public void reset() {
        initialized = false;
        lastSampleAt = 0;
        repairUntil = 0;
        clearEvidence();
    }

    private void clearEvidence() {
        firstFalseAt = 0;
        lastFalseAt = 0;
        falseClaims = 0;
    }

    private static double square(double value) { return value * value; }
    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
