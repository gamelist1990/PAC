package org.pexserver.pac.movement;

/**
 * Detects client-side position jumps that cannot be explained by elapsed packet
 * time. In particular, LiquidBounce PaperBypass pads a teleport with repeated
 * identical movement packets and then sends a distant coordinate.
 */
public final class RapidPositionJumpWindow {
    private static final long MAX_RAPID_GAP_NANOS = 250_000_000L;
    private static final long HARD_JUMP_GAP_NANOS = 100_000_000L;
    private static final double SAME_POSITION_EPSILON_SQUARED = 1.0E-10;
    private static final double PADDED_JUMP_DISTANCE = 8.0;
    private static final double HARD_JUMP_DISTANCE = 16.0;

    public record Finding(boolean impossible, double distance, int repeatedPositions,
                          long elapsedMillis) {
        static Finding valid() { return new Finding(false, 0, 0, 0); }
    }

    private boolean initialized;
    private double x, y, z;
    private long lastAt;
    private int repeatedPositions;

    public Finding accept(boolean hasPosition, double nextX, double nextY, double nextZ,
                          long nowNanos, boolean timingUncertain,
                          boolean movementSuppressed, boolean externalMotion,
                          boolean authorizedFlight) {
        if (!hasPosition) return Finding.valid();
        if (!Double.isFinite(nextX) || !Double.isFinite(nextY) || !Double.isFinite(nextZ)
                || timingUncertain || movementSuppressed || externalMotion || authorizedFlight) {
            reset();
            return Finding.valid();
        }
        if (!initialized || nowNanos < lastAt || nowNanos - lastAt > MAX_RAPID_GAP_NANOS) {
            seed(nextX, nextY, nextZ, nowNanos);
            return Finding.valid();
        }

        long elapsed = nowNanos - lastAt;
        double dx = nextX - x;
        double dy = nextY - y;
        double dz = nextZ - z;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared <= SAME_POSITION_EPSILON_SQUARED) {
            repeatedPositions = Math.min(32, repeatedPositions + 1);
            lastAt = nowNanos;
            return Finding.valid();
        }

        double distance = Math.sqrt(distanceSquared);
        int repeats = repeatedPositions;
        boolean paddedTeleport = repeats >= 2 && distance > PADDED_JUMP_DISTANCE;
        boolean hardJump = elapsed <= HARD_JUMP_GAP_NANOS && distance > HARD_JUMP_DISTANCE;

        x = nextX;
        y = nextY;
        z = nextZ;
        lastAt = nowNanos;
        repeatedPositions = 0;

        return paddedTeleport || hardJump
                ? new Finding(true, distance, repeats, elapsed / 1_000_000L)
                : Finding.valid();
    }

    public void reset() {
        initialized = false;
        lastAt = 0;
        repeatedPositions = 0;
    }

    private void seed(double x, double y, double z, long nowNanos) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.lastAt = nowNanos;
        this.repeatedPositions = 0;
        this.initialized = true;
    }
}
