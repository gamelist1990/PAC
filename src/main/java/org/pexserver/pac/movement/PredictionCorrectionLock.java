package org.pexserver.pac.movement;

/** Holds a movement setback while an already-flagged prediction keeps failing. */
public final class PredictionCorrectionLock {
    public record Position(double x, double y, double z) { }

    private static final int VALID_SAMPLES_TO_RELEASE = 3;
    private Position anchor;
    private int validSamples;

    /** Start enforcement at the position immediately before the rejected packet. */
    public void begin(Position position) {
        if (position == null || !finite(position.x(), position.y(), position.z())) return;
        if (anchor == null) anchor = position;
        validSamples = 0;
    }

    /**
     * Returns true when this sample should also be rejected. Untrusted movement
     * samples do not count toward release, and known plugin/velocity movement
     * ends the lock so external motion is not held back.
     */
    public boolean reject(boolean evaluated, boolean suspicious, boolean externalMotion) {
        if (anchor == null) return false;
        if (externalMotion) {
            clear();
            return false;
        }
        if (!evaluated) return false;
        if (suspicious) {
            validSamples = 0;
            return true;
        }
        if (++validSamples >= VALID_SAMPLES_TO_RELEASE) clear();
        return false;
    }

    public Position anchor() { return anchor; }
    public boolean active() { return anchor != null; }
    /** Block fresh coordinates while the server is synchronizing this setback. */
    public boolean holdDuringSync(boolean positionChanged, boolean movementSuppressed) {
        return anchor != null && positionChanged && movementSuppressed;
    }

    public void clear() {
        anchor = null;
        validSamples = 0;
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
